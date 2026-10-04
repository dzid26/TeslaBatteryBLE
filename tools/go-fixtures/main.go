package main

import (
	"bytes"
	"crypto/x509"
	"encoding/hex"
	"fmt"

	"google.golang.org/protobuf/proto"

	"github.com/teslamotors/vehicle-command/internal/authentication"
	"github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/signatures"
	universal "github.com/teslamotors/vehicle-command/pkg/protocol/protobuf/universalmessage"
)

const vin = "5YJ3E1EA7KF000001"

func deterministicKey(seed byte) authentication.ECDHPrivateKey {
	buf := make([]byte, 1024)
	for i := range buf {
		buf[i] = seed + byte(i)
	}
	key, err := authentication.NewECDHPrivateKey(bytes.NewReader(buf))
	if err != nil {
		panic(err)
	}
	return key
}

func main() {
	client := deterministicKey(1)
	vehicle := deterministicKey(2)

	challenge := make([]byte, 16)
	for i := range challenge {
		challenge[i] = 0xA0 + byte(i)
	}

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

	verifier, err := authentication.NewVerifier(
		vehicle,
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
