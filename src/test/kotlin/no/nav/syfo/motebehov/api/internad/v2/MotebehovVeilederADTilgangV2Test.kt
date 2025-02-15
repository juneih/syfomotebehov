package no.nav.syfo.motebehov.api.internad.v2

import no.nav.security.token.support.core.context.TokenValidationContextHolder
import no.nav.syfo.LocalApplication
import no.nav.syfo.testhelper.OidcTestHelper.loggInnVeilederADV2
import no.nav.syfo.testhelper.OidcTestHelper.loggUtAlle
import no.nav.syfo.testhelper.UserConstants.ARBEIDSTAKER_FNR
import no.nav.syfo.testhelper.UserConstants.VEILEDER_ID
import no.nav.syfo.testhelper.mockSvarFraSyfoTilgangskontrollV2TilgangTilBruker
import org.junit.jupiter.api.*
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.*
import java.text.ParseException
import javax.inject.Inject
import javax.ws.rs.ForbiddenException

@ExtendWith(SpringExtension::class)
@SpringBootTest(classes = [LocalApplication::class])
@DirtiesContext
class MotebehovVeilederADTilgangV2Test {

    @Value("\${azure.openid.config.token.endpoint}")
    private lateinit var azureTokenEndpoint: String

    @Value("\${tilgangskontrollapi.url}")
    private lateinit var tilgangskontrollUrl: String

    @Inject
    private lateinit var motebehovVeilederController: MotebehovVeilederADControllerV2

    @Inject
    private lateinit var contextHolder: TokenValidationContextHolder

    @Inject
    private lateinit var restTemplate: RestTemplate

    private lateinit var mockRestServiceServer: MockRestServiceServer

    @Inject
    @Qualifier("restTemplateWithProxy")
    private lateinit var restTemplateWithProxy: RestTemplate
    private lateinit var mockRestServiceWithProxyServer: MockRestServiceServer

    @BeforeEach
    fun setUp() {
        mockRestServiceServer = MockRestServiceServer.bindTo(restTemplate).build()
        mockRestServiceWithProxyServer = MockRestServiceServer.bindTo(restTemplateWithProxy).build()
    }

    @AfterEach
    fun tearDown() {
        mockRestServiceServer.verify()
        mockRestServiceWithProxyServer.verify()
        loggUtAlle(contextHolder)
    }

    @Test
    @Throws(ParseException::class)
    fun `veileder nektes tilgang`() {
        loggInnVeilederADV2(contextHolder, VEILEDER_ID)
        mockSvarFraSyfoTilgangskontroll(ARBEIDSTAKER_FNR, HttpStatus.FORBIDDEN)
        assertThrows<ForbiddenException> { motebehovVeilederController.hentMotebehovListe(ARBEIDSTAKER_FNR) }
    }

    @Test
    @Throws(ParseException::class)
    fun `klientfeil mot Syfotilgangskontroll`() {
        loggInnVeilederADV2(contextHolder, VEILEDER_ID)
        mockSvarFraSyfoTilgangskontroll(ARBEIDSTAKER_FNR, HttpStatus.BAD_REQUEST)
        assertThrows<HttpClientErrorException> { motebehovVeilederController.hentMotebehovListe(ARBEIDSTAKER_FNR) }
    }

    @Test
    @Throws(ParseException::class)
    fun `teknisk feil i Syfotilgangskontroll`() {
        loggInnVeilederADV2(contextHolder, VEILEDER_ID)
        mockSvarFraSyfoTilgangskontroll(ARBEIDSTAKER_FNR, HttpStatus.INTERNAL_SERVER_ERROR)
        assertThrows<HttpServerErrorException> { motebehovVeilederController.hentMotebehovListe(ARBEIDSTAKER_FNR) }
    }

    private fun mockSvarFraSyfoTilgangskontroll(
        fnr: String,
        status: HttpStatus
    ) {
        mockSvarFraSyfoTilgangskontrollV2TilgangTilBruker(
            azureTokenEndpoint = azureTokenEndpoint,
            tilgangskontrollUrl = tilgangskontrollUrl,
            mockRestServiceServer = mockRestServiceServer,
            mockRestServiceWithProxyServer = mockRestServiceWithProxyServer,
            status = status,
            fnr = fnr
        )
    }
}
