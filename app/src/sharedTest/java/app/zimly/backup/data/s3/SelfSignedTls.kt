package app.zimly.backup.data.s3

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/** A freshly generated self-signed certificate, valid for [hostNames]. */
fun selfSignedCertificate(vararg hostNames: String): HeldCertificate =
    HeldCertificate.Builder()
        .apply { hostNames.forEach { addSubjectAlternativeName(it) } }
        .build()

/** The app's S3 HTTP client ([MinioRepository.client]), trusting exactly [certificate]. */
fun clientTrusting(certificate: HeldCertificate): OkHttpClient {
    val trust = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate)
        .build()
    return MinioRepository.client().newBuilder()
        .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
        .build()
}

/**
 * Makes this server serve HTTPS with a freshly generated self-signed certificate, and returns a
 * client that trusts exactly that certificate.
 */
fun MockWebServer.serveSelfSignedHttps(): OkHttpClient {
    val certificate = selfSignedCertificate(hostName)
    val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(certificate)
        .build()
    useHttps(serverCertificates.sslSocketFactory(), false)
    return clientTrusting(certificate)
}
