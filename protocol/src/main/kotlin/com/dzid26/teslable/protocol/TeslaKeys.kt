// SPDX-License-Identifier: AGPL-3.0-only
package com.dzid26.teslable.protocol

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.EC
import dev.whyoleg.cryptography.algorithms.ECDH
import dev.whyoleg.cryptography.algorithms.SHA1

data class TeslaKeyPair(
    val privateKeyPkcs8: ByteArray,
    val publicKeyRaw: ByteArray,
) {
    @OptIn(DelicateCryptographyApi::class)
    val keyId: ByteArray
        // Truncated SHA-1 is mandated by the Tesla protocol, not our choice.
        get() = sha1.hasher().hashBlocking(publicKeyRaw).copyOf(4)
}

@OptIn(DelicateCryptographyApi::class)
object TeslaKeys {
    private val ecdh = CryptographyProvider.Default.get(ECDH)

    fun generate(): TeslaKeyPair {
        val keyPair = ecdh.keyPairGenerator(EC.Curve.P256).generateKeyBlocking()
        return TeslaKeyPair(
            privateKeyPkcs8 = keyPair.privateKey.encodeToByteArrayBlocking(EC.PrivateKey.Format.DER.Generic),
            // Uncompressed point; the car and the Go implementation agree on it.
            publicKeyRaw = keyPair.publicKey.encodeToByteArrayBlocking(EC.PublicKey.Format.RAW),
        )
    }
}

@OptIn(DelicateCryptographyApi::class)
private val sha1 = CryptographyProvider.Default.get(SHA1)
