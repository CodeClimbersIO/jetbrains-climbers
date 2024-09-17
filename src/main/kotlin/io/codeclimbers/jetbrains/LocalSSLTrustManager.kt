package io.codeclimbers.jetbrains

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

class LocalSSLTrustManager : X509TrustManager {
    @Throws(CertificateException::class)
    override fun checkClientTrusted(arg0: Array<X509Certificate?>?, arg1: String?) {
        // TODO Auto-generated method stub
    }

    @Throws(CertificateException::class)
    override fun checkServerTrusted(arg0: Array<X509Certificate?>?, arg1: String?) {
        // TODO Auto-generated method stub
    }

    override fun getAcceptedIssuers(): Array<X509Certificate?> {
        // TODO Auto-generated method stub
        return emptyArray()
    }
}
