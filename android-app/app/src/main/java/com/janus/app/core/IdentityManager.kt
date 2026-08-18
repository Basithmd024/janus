package com.janus.app.core

import android.content.Context
import android.util.Base64
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date

data class Identity(
    val certPem: String,
    val keyPem: String,
    val fingerprint: String,
    val keyPair: KeyPair? = null
)

object IdentityManager {
    private const val CERT_FILE_NAME = "cert.pem"
    private const val KEY_FILE_NAME = "key.pem"
    private var cachedIdentity: Identity? = null

    @Synchronized
    fun getOrGenerateIdentity(context: Context): Identity {
        cachedIdentity?.let { return it }

        val certFile = File(context.filesDir, CERT_FILE_NAME)
        val keyFile = File(context.filesDir, KEY_FILE_NAME)

        // Check if previously persisted identity exists
        if (certFile.exists() && keyFile.exists() && certFile.length() > 0 && keyFile.length() > 0) {
            try {
                val certPem = certFile.readText()
                val keyPem = keyFile.readText()
                val fingerprint = computeFingerprint(certPem)
                Log.d("IdentityManager", "Loaded existing persistent identity. Fingerprint: $fingerprint")
                val id = Identity(certPem, keyPem, fingerprint, null)
                cachedIdentity = id
                return id
            } catch (e: Exception) {
                Log.e("IdentityManager", "Failed to load cached identity, generating new one", e)
            }
        }

        // Generate key pair once
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()

        // Generate self-signed certificate
        val cert = generateSelfSignedCertificate(keyPair)
        
        // Convert to PEM strings
        val certPem = encodeCertToPem(cert)
        val keyPem = encodeKeyToPem(keyPair.private)

        // Save to internal files for permanent persistence
        certFile.writeText(certPem)
        keyFile.writeText(keyPem)

        val fingerprint = computeFingerprint(certPem)
        Log.d("IdentityManager", "Generated and saved new persistent identity. Fingerprint: $fingerprint")
        val id = Identity(certPem, keyPem, fingerprint, keyPair)
        cachedIdentity = id
        return id
    }

    private fun generateSelfSignedCertificate(keyPair: KeyPair): X509Certificate {
        val sub = X500Name("CN=Janus Android Node")
        val serial = BigInteger.valueOf(System.currentTimeMillis())
        
        val start = Date()
        val calendar = Calendar.getInstance()
        calendar.time = start
        calendar.add(Calendar.YEAR, 10)
        val end = calendar.time

        val builder = JcaX509v3CertificateBuilder(
            sub,
            serial,
            start,
            end,
            sub,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter().getCertificate(holder)
    }

    private fun encodeCertToPem(cert: X509Certificate): String {
        val certBase64 = Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
        return "-----BEGIN CERTIFICATE-----\n$certBase64\n-----END CERTIFICATE-----\n"
    }

    private fun encodeKeyToPem(key: PrivateKey): String {
        val keyBase64 = Base64.encodeToString(key.encoded, Base64.NO_WRAP)
        return "-----BEGIN PRIVATE KEY-----\n$keyBase64\n-----END PRIVATE KEY-----\n"
    }

    fun computeFingerprint(certPem: String): String {
        val cleanPem = certPem
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s".toRegex(), "")
        val derBytes = Base64.decode(cleanPem, Base64.DEFAULT)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(derBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
