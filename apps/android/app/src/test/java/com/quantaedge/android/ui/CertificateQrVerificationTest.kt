package com.quantaedge.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CertificateQrVerificationTest {
    private val code = "QE-2026-ABCDEF1234567890"

    @Test
    fun acceptsTheRawCertificateNumberFromASeparateLinearBarcode() {
        assertEquals(code, parseQuantaEdgeCertificateQr(code, false))
        assertEquals(code, parseQuantaEdgeCertificateQr(code.lowercase(), false))
    }

    @Test
    fun acceptsCanonicalProductionVerificationUrl() {
        assertEquals(
            code,
            parseQuantaEdgeCertificateQr("https://qe.thinkwithsujeet.in/certificate/verify?code=$code", false)
        )
    }

    @Test
    fun acceptsOfficialVerificationApiUrlFromACode128Barcode() {
        assertEquals(
            code,
            parseQuantaEdgeCertificateQr("https://qe.thinkwithsujeet.in/api/v1/certificates/verify/$code", false)
        )
        assertNull(
            parseQuantaEdgeCertificateQr("https://qe.thinkwithsujeet.in/api/v1/certificates/verify/$code/other", false)
        )
        assertNull(
            parseQuantaEdgeCertificateQr("https://example.com/api/v1/certificates/verify/$code", false)
        )
    }

    @Test
    fun acceptsLocalhostOnlyForDebugBuilds() {
        assertEquals(
            code,
            parseQuantaEdgeCertificateQr("http://localhost:3000/certificate/verify?code=$code", true)
        )
        assertNull(
            parseQuantaEdgeCertificateQr("http://localhost:3000/certificate/verify?code=$code", false)
        )
    }

    @Test
    fun rejectsUnknownHostsAndLookalikeDomains() {
        assertNull(parseQuantaEdgeCertificateQr("https://example.com/certificate/verify?code=$code", false))
        assertNull(parseQuantaEdgeCertificateQr("https://qe.thinkwithsujeet.in.attacker.example/certificate/verify?code=$code", false))
    }

    @Test
    fun rejectsWrongPathInvalidNumbersAndDuplicateCodes() {
        assertNull(parseQuantaEdgeCertificateQr("https://qe.thinkwithsujeet.in/other?code=$code", false))
        assertNull(parseQuantaEdgeCertificateQr("https://qe.thinkwithsujeet.in/certificate/verify?code=bad", false))
        assertNull(parseQuantaEdgeCertificateQr("https://qe.thinkwithsujeet.in/certificate/verify?code=$code&code=$code", false))
    }

    @Test
    fun rejectsUserInfoAndFragments() {
        assertNull(parseQuantaEdgeCertificateQr("https://attacker@qe.thinkwithsujeet.in/certificate/verify?code=$code", false))
        assertNull(parseQuantaEdgeCertificateQr("https://qe.thinkwithsujeet.in/certificate/verify?code=$code#fake", false))
    }
}
