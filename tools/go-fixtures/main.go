// SPDX-License-Identifier: AGPL-3.0-only
package main

import (
	"bytes"
	"crypto/rand"
	"crypto/x509"
	"encoding/hex"
	"fmt"
	"time"

	"google.golang.org/protobuf/proto"

	"github.com/teslamotors/vehicle-command/internal/authentication"
	"github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/signatures"
	universal "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/universalmessage"
)

const vin = "5YJ3E1EA7KF000001"

// requestExpiresIn is the Go dispatcher's default command lifetime, in seconds.
const requestExpiresIn = 5

func fixedKey(start byte) authentication.ECDHPrivateKey {
	scalar := make([]byte, 32)
	for i := range scalar {
		scalar[i] = start + byte(i)
	}
	key := authentication.UnmarshalECDHPrivateKey(scalar)
	if key == nil {
		panic("invalid scalar")
	}
	return key
}

func sequence(start byte, length int) []byte {
	out := make([]byte, length)
	for i := range out {
		out[i] = start + byte(i)
	}
	return out
}

// fixedNonceKey wraps a key so that the sessions it derives encrypt with a
// fixed nonce, which makes the Go output reproducible. Key agreement, key
// derivation, AES-GCM and the session info HMAC stay the Go implementation's.
type fixedNonceKey struct {
	authentication.ECDHPrivateKey
	nonce []byte
}

func (k fixedNonceKey) Exchange(remotePublicBytes []byte) (authentication.Session, error) {
	session, err := k.ECDHPrivateKey.Exchange(remotePublicBytes)
	if err != nil {
		return nil, err
	}
	return fixedNonceSession{Session: session, nonce: k.nonce}, nil
}

type fixedNonceSession struct {
	authentication.Session
	nonce []byte
}

// Encrypt runs the Go session's own Encrypt. NativeSession draws its nonce
// from crypto/rand, so the fixed nonce is served as the random source for
// the duration of the call.
func (s fixedNonceSession) Encrypt(plaintext, associatedData []byte) (nonce, ciphertext, tag []byte, err error) {
	random := rand.Reader
	rand.Reader = bytes.NewReader(s.nonce)
	defer func() { rand.Reader = random }()
	nonce, ciphertext, tag, err = s.Session.Encrypt(plaintext, associatedData)
	if err == nil && !bytes.Equal(nonce, s.nonce) {
		panic("the Go session did not encrypt with the fixed nonce")
	}
	return nonce, ciphertext, tag, err
}

func main() {
	client := fixedKey(1)
	vehicle := fixedKey(0x21)

	challenge := sequence(0xA0, 16)

	info := &signatures.SessionInfo{
		Counter:   41,
		PublicKey: vehicle.PublicBytes(),
		Epoch:     bytes.Repeat([]byte{0x5A}, 16),
		ClockTime: 12345,
		Handle:    7,
	}
	encodedInfo, err := proto.Marshal(info)
	if err != nil {
		panic(err)
	}

	session, err := client.Exchange(vehicle.PublicBytes())
	if err != nil {
		panic(err)
	}
	tag, err := session.SessionInfoHMAC([]byte(vin), challenge, encodedInfo)
	if err != nil {
		panic(err)
	}

	native := client.(*authentication.NativeECDHKey)
	pkcs8, err := x509.MarshalPKCS8PrivateKey(native.PrivateKey)
	if err != nil {
		panic(err)
	}

	fmt.Printf("CLIENT_PRIVATE_PKCS8=%s\n", hex.EncodeToString(pkcs8))
	fmt.Printf("CLIENT_PUBLIC=%s\n", hex.EncodeToString(client.PublicBytes()))
	fmt.Printf("VEHICLE_PUBLIC=%s\n", hex.EncodeToString(vehicle.PublicBytes()))
	fmt.Printf("SESSION_INFO=%s\n", hex.EncodeToString(encodedInfo))
	fmt.Printf("CHALLENGE=%s\n", hex.EncodeToString(challenge))
	fmt.Printf("SESSION_INFO_TAG=%s\n", hex.EncodeToString(tag))

	// Request direction: the Go Signer, authenticated with the session info
	// above (counter, epoch and clock time), encrypts a command with the
	// fields TeslaSessionRequests.buildAuthenticatedRequest sets.
	requestNonce := sequence(0xC0, 12)
	signer, err := authentication.NewAuthenticatedSigner(
		fixedNonceKey{client, requestNonce},
		[]byte(vin),
		challenge,
		encodedInfo,
		tag,
	)
	if err != nil {
		panic(err)
	}

	routingAddress := sequence(0xB0, 16)
	requestUUID := sequence(0xE0, 16)
	requestPayload := []byte("charge-state-request")
	request := &universal.RoutableMessage{
		ToDestination: &universal.Destination{
			SubDestination: &universal.Destination_Domain{
				Domain: universal.Domain_DOMAIN_INFOTAINMENT,
			},
		},
		FromDestination: &universal.Destination{
			SubDestination: &universal.Destination_RoutingAddress{
				RoutingAddress: routingAddress,
			},
		},
		Payload: &universal.RoutableMessage_ProtobufMessageAsBytes{
			ProtobufMessageAsBytes: requestPayload,
		},
		Uuid:  requestUUID,
		Flags: 1 << universal.Flags_FLAG_ENCRYPT_RESPONSE,
	}
	if err := signer.Encrypt(request, requestExpiresIn*time.Second); err != nil {
		panic(err)
	}
	// The Signer measures time from its import of the session info, so
	// expires_at is the session clock time plus requestExpiresIn as long as
	// Encrypt runs within a second of the import.
	expiresAt := request.GetSignatureData().GetAES_GCM_PersonalizedData().GetExpiresAt()
	if expiresAt != info.ClockTime+requestExpiresIn {
		panic(fmt.Sprintf("expires_at %d is not clock time + %d", expiresAt, requestExpiresIn))
	}
	encodedRequest, err := proto.Marshal(request)
	if err != nil {
		panic(err)
	}

	fmt.Printf("REQUEST_ROUTING_ADDRESS=%s\n", hex.EncodeToString(routingAddress))
	fmt.Printf("REQUEST_UUID=%s\n", hex.EncodeToString(requestUUID))
	fmt.Printf("REQUEST_PAYLOAD=%s\n", hex.EncodeToString(requestPayload))
	fmt.Printf("REQUEST_EXPIRES_IN=%d\n", requestExpiresIn)
	fmt.Printf("REQUEST_NONCE=%s\n", hex.EncodeToString(requestNonce))
	fmt.Printf("REQUEST_MESSAGE=%s\n", hex.EncodeToString(encodedRequest))
	fmt.Printf("REQUEST_ID=%s\n", hex.EncodeToString(authentication.RequestID(request)))

	// Response direction: the vehicle's Verifier encrypts a reply, again
	// with a fixed nonce so that RESPONSE_MESSAGE is reproducible.
	verifier, err := authentication.NewVerifier(
		fixedNonceKey{vehicle, sequence(0xD0, 12)},
		[]byte(vin),
		universal.Domain_DOMAIN_INFOTAINMENT,
		client.PublicBytes(),
	)
	if err != nil {
		panic(err)
	}

	requestID := bytes.Repeat([]byte{0x11}, 17)
	plaintext := []byte("charge-state-fixture")
	message := &universal.RoutableMessage{
		FromDestination: &universal.Destination{
			SubDestination: &universal.Destination_Domain{
				Domain: universal.Domain_DOMAIN_INFOTAINMENT,
			},
		},
		Payload: &universal.RoutableMessage_ProtobufMessageAsBytes{
			ProtobufMessageAsBytes: plaintext,
		},
	}
	if err := verifier.Encrypt(message, requestID, 9); err != nil {
		panic(err)
	}
	encodedMessage, err := proto.Marshal(message)
	if err != nil {
		panic(err)
	}

	fmt.Printf("RESPONSE_MESSAGE=%s\n", hex.EncodeToString(encodedMessage))
	fmt.Printf("RESPONSE_REQUEST_ID=%s\n", hex.EncodeToString(requestID))
	fmt.Printf("RESPONSE_PLAINTEXT=%s\n", hex.EncodeToString(plaintext))
}
