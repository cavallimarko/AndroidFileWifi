package hr.gd.androidfilewifi.desktop

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.cert.X509Certificate
import java.util.Date

/**
 * The receiver's TLS identity: a self-signed certificate generated on first run.
 * Phones pin its SHA-256 [fingerprint] (obtained from the QR code), so no CA is involved.
 */
class Identity(val keyStore: KeyStore, val alias: String, val password: CharArray) {
    val fingerprint: String = fingerprintOf(keyStore.getCertificate(alias) as X509Certificate)

    companion object {
        private const val ALIAS = "afw"
        // The keystore lives in the user's own AppData folder; the password is not a secret
        // boundary, only a requirement of the keystore API.
        private val PASSWORD = "afw-local-keystore".toCharArray()

        fun fingerprintOf(cert: X509Certificate): String = sha256Hex(cert.encoded)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * Key and certificate are kept as plain DER files in the user's AppData folder and loaded into an
         * in-memory keystore. (PKCS12 is avoided on purpose: its key encryption breaks once Netty
         * registers BouncyCastle as a JCA provider.)
         */
        fun loadOrCreate(dir: File): Identity {
            val certFile = File(dir, "identity.crt")
            val keyFile = File(dir, "identity.key")
            if (certFile.isFile && keyFile.isFile) {
                runCatching {
                    val cert = CertificateFactory.getInstance("X.509")
                        .generateCertificate(certFile.inputStream()) as X509Certificate
                    val key = KeyFactory.getInstance("RSA")
                        .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    return Identity(keystoreOf(key, cert), ALIAS, PASSWORD)
                }
            }
            val (key, cert) = generate()
            dir.mkdirs()
            certFile.writeBytes(cert.encoded)
            keyFile.writeBytes(key.encoded)
            return Identity(keystoreOf(key, cert), ALIAS, PASSWORD)
        }

        private fun keystoreOf(key: PrivateKey, cert: X509Certificate): KeyStore =
            KeyStore.getInstance("JKS").apply {
                load(null, null)
                setKeyEntry(ALIAS, key, PASSWORD, arrayOf(cert))
            }

        private fun generate(): Pair<PrivateKey, X509Certificate> {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val subject = X500Name("CN=AndroidFileWifi")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(64, SecureRandom()),
                Date(now - 24 * 3600_000L),
                Date(now + 20L * 365 * 24 * 3600_000L),
                subject,
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            return keyPair.private to cert
        }
    }
}
