package no.nav.syfo.motebehov.api

import no.nav.security.oidc.context.OIDCRequestContextHolder
import no.nav.syfo.LocalApplication
import no.nav.syfo.consumer.aktorregister.AktorregisterConsumer
import no.nav.syfo.consumer.aktorregister.domain.Fodselsnummer
import no.nav.syfo.consumer.mote.MoteConsumer
import no.nav.syfo.consumer.pdl.PdlConsumer
import no.nav.syfo.consumer.sts.StsConsumer
import no.nav.syfo.motebehov.database.MotebehovDAO
import no.nav.syfo.motebehov.motebehovstatus.DAYS_END_DIALOGMOTE2
import no.nav.syfo.motebehov.motebehovstatus.DAYS_START_DIALOGMOTE2
import no.nav.syfo.motebehov.motebehovstatus.MotebehovSkjemaType
import no.nav.syfo.motebehov.motebehovstatus.MotebehovStatus
import no.nav.syfo.oppfolgingstilfelle.database.OppfolgingstilfelleDAO
import no.nav.syfo.oversikthendelse.OversikthendelseProducer
import no.nav.syfo.testhelper.OidcTestHelper.loggInnBruker
import no.nav.syfo.testhelper.OidcTestHelper.loggUtAlle
import no.nav.syfo.testhelper.UserConstants.ARBEIDSTAKER_AKTORID
import no.nav.syfo.testhelper.UserConstants.ARBEIDSTAKER_FNR
import no.nav.syfo.testhelper.UserConstants.VIRKSOMHETSNUMMER
import no.nav.syfo.testhelper.generator.MotebehovGenerator
import no.nav.syfo.testhelper.generator.generateOversikthendelsetilfelle
import no.nav.syfo.testhelper.generator.generatePdlHentPerson
import no.nav.syfo.testhelper.mockAndExpectBehandlendeEnhetRequest
import no.nav.syfo.testhelper.mockAndExpectSTSService
import org.assertj.core.api.Assertions
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.cache.CacheManager
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.junit4.SpringRunner
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.web.client.RestTemplate
import java.time.LocalDate
import java.util.function.Consumer
import javax.inject.Inject

@RunWith(SpringRunner::class)
@SpringBootTest(classes = [LocalApplication::class])
@DirtiesContext
class MotebehovArbeidstakerV2Test {
    @Value("\${syfobehandlendeenhet.url}")
    private lateinit var behandlendeenhetUrl: String

    @Value("\${syfobrukertilgang.url}")
    private lateinit var brukertilgangUrl: String

    @Value("\${security.token.service.rest.url}")
    private lateinit var stsUrl: String

    @Value("\${srv.username}")
    private lateinit var srvUsername: String

    @Value("\${srv.password}")
    private lateinit var srvPassword: String

    @Inject
    private lateinit var motebehovArbeidstakerController: MotebehovArbeidstakerV2Controller

    @Inject
    private lateinit var oidcRequestContextHolder: OIDCRequestContextHolder

    @Inject
    private lateinit var motebehovDAO: MotebehovDAO

    @Inject
    private lateinit var cacheManager: CacheManager

    @Inject
    private lateinit var stsConsumer: StsConsumer

    @Inject
    private lateinit var oppfolgingstilfelleDAO: OppfolgingstilfelleDAO

    @Inject
    private lateinit var restTemplate: RestTemplate

    @MockBean
    private lateinit var aktorregisterConsumer: AktorregisterConsumer

    @MockBean
    private lateinit var moteConsumer: MoteConsumer

    @MockBean
    private lateinit var pdlConsumer: PdlConsumer

    @MockBean
    private lateinit var oversikthendelseProducer: OversikthendelseProducer

    private lateinit var mockRestServiceServer: MockRestServiceServer

    private val motebehovGenerator = MotebehovGenerator()

    @Before
    fun setUp() {
        `when`(aktorregisterConsumer.getAktorIdForFodselsnummer(Fodselsnummer(ARBEIDSTAKER_FNR))).thenReturn(ARBEIDSTAKER_AKTORID)
        `when`(pdlConsumer.person(Fodselsnummer(ARBEIDSTAKER_FNR))).thenReturn(generatePdlHentPerson(null, null))
        mockRestServiceServer = MockRestServiceServer.bindTo(restTemplate).build()
        loggInnBruker(oidcRequestContextHolder, ARBEIDSTAKER_FNR)
        cleanDB()
    }

    @After
    fun tearDown() {
        loggUtAlle(oidcRequestContextHolder)
        mockRestServiceServer.reset()
        cacheManager.cacheNames
                .forEach(Consumer { cacheName: String ->
                    val cache = cacheManager.getCache(cacheName)
                    cache?.clear()
                })
        cleanDB()
    }

    @Test
    fun getMotebehovStatusWithNoOppfolgingstilfelle() {
        val motebehovStatus = motebehovArbeidstakerController.motebehovStatusArbeidstaker()
        Assert.assertFalse(motebehovStatus.visMotebehov)
        Assert.assertNull(motebehovStatus.skjemaType)
        Assert.assertNull(motebehovStatus.motebehov)
    }

    @Test
    fun getAsArbeidstakerMotebehovStatusWithTodayOutsideOppfolgingstilfelle() {
        loggUtAlle(oidcRequestContextHolder)
        loggInnBruker(oidcRequestContextHolder, ARBEIDSTAKER_FNR)

        oppfolgingstilfelleDAO.create(generateOversikthendelsetilfelle.copy(
                fom = LocalDate.now().minusDays(10),
                tom = LocalDate.now().minusDays(1)
        ))
        val motebehovStatus = motebehovArbeidstakerController.motebehovStatusArbeidstaker()
        Assert.assertFalse(motebehovStatus.visMotebehov)
        Assert.assertNull(motebehovStatus.skjemaType)
        Assert.assertNull(motebehovStatus.motebehov)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleBeforeDialogmote2StartDate() {
        oppfolgingstilfelleDAO.create(generateOversikthendelsetilfelle.copy(
                fom = LocalDate.now().minusDays(DAYS_START_DIALOGMOTE2).plusDays(1),
                tom = LocalDate.now().plusDays(1)
        ))
        val motebehovStatus = motebehovArbeidstakerController.motebehovStatusArbeidstaker()
        Assert.assertFalse(motebehovStatus.visMotebehov)
        Assert.assertNull(motebehovStatus.skjemaType)
        Assert.assertNull(motebehovStatus.motebehov)
    }

    @Test
    fun getMotebehovStatusWithTodayInsideOppfolgingstilfelleAfterDialogmote2EndDate() {
        oppfolgingstilfelleDAO.create(generateOversikthendelsetilfelle.copy(
                fom = LocalDate.now().minusDays(DAYS_END_DIALOGMOTE2),
                tom = LocalDate.now().plusDays(1)
        ))
        val motebehovStatus = motebehovArbeidstakerController.motebehovStatusArbeidstaker()
        Assert.assertFalse(motebehovStatus.visMotebehov)
        Assert.assertNull(motebehovStatus.skjemaType)
        Assert.assertNull(motebehovStatus.motebehov)
    }

    @Test
    fun getMotebehovStatusWithNoMotebehovAndNoMoteInsideInsideDialogmote2UpperLimit() {
        val kOppfolgingstilfelle = generateOversikthendelsetilfelle.copy(
                fom = LocalDate.now().minusDays(DAYS_END_DIALOGMOTE2).plusDays(1),
                tom = LocalDate.now().plusDays(1)
        )
        `when`(moteConsumer.erMoteOpprettetForArbeidstakerEtterDato(
                ARBEIDSTAKER_AKTORID,
                kOppfolgingstilfelle.fom.atStartOfDay()
        )).thenReturn(false)

        oppfolgingstilfelleDAO.create(kOppfolgingstilfelle)

        val motebehovStatus = motebehovArbeidstakerController.motebehovStatusArbeidstaker()
        Assert.assertTrue(motebehovStatus.visMotebehov)
        Assert.assertEquals(MotebehovSkjemaType.SVAR_BEHOV, motebehovStatus.skjemaType)
        Assert.assertNull(motebehovStatus.motebehov)
    }

    @Test
    fun getMotebehovStatusWithNoMotebehovAndNoMoteInsideInsideDialogmote2LowerLimit() {
        val kOppfolgingstilfelle = generateOversikthendelsetilfelle.copy(
                fom = LocalDate.now().minusDays(DAYS_START_DIALOGMOTE2),
                tom = LocalDate.now().plusDays(1)
        )
        `when`(moteConsumer.erMoteOpprettetForArbeidstakerEtterDato(
                ARBEIDSTAKER_AKTORID,
                kOppfolgingstilfelle.fom.atStartOfDay()
        )).thenReturn(false)

        oppfolgingstilfelleDAO.create(kOppfolgingstilfelle)

        val motebehovStatus = motebehovArbeidstakerController.motebehovStatusArbeidstaker()
        Assert.assertTrue(motebehovStatus.visMotebehov)
        Assert.assertEquals(MotebehovSkjemaType.SVAR_BEHOV, motebehovStatus.skjemaType)
        Assert.assertNull(motebehovStatus.motebehov)
    }

    @Test
    fun getMotebehovStatusWithNoMotebehovAndNoMote() {
        val kOppfolgingstilfelle = generateOversikthendelsetilfelle
        `when`(moteConsumer.erMoteOpprettetForArbeidstakerEtterDato(
                ARBEIDSTAKER_AKTORID,
                kOppfolgingstilfelle.fom.atStartOfDay()
        )).thenReturn(false)

        oppfolgingstilfelleDAO.create(kOppfolgingstilfelle)

        val motebehovStatus = motebehovArbeidstakerController.motebehovStatusArbeidstaker()
        Assert.assertTrue(motebehovStatus.visMotebehov)
        Assert.assertEquals(MotebehovSkjemaType.SVAR_BEHOV, motebehovStatus.skjemaType)
        Assert.assertNull(motebehovStatus.motebehov)
    }

    @Test
    fun getMotebehovStatusWithNoMotebehovAndMote() {
        val kOppfolgingstilfelle = generateOversikthendelsetilfelle
        `when`(moteConsumer.erMoteOpprettetForArbeidstakerEtterDato(
                ARBEIDSTAKER_AKTORID,
                kOppfolgingstilfelle.fom.atStartOfDay()
        )).thenReturn(true)

        oppfolgingstilfelleDAO.create(kOppfolgingstilfelle)

        val motebehovStatus = motebehovArbeidstakerController.motebehovStatusArbeidstaker()
        Assert.assertFalse(motebehovStatus.visMotebehov)
        Assert.assertNull(motebehovStatus.skjemaType)
        Assert.assertNull(motebehovStatus.motebehov)
    }


    @Test
    fun getAsArbeidstakerMotebehovStatusAndSendOversikthendelseWithMotebehovHarBehovTrue() {
        oppfolgingstilfelleDAO.create(generateOversikthendelsetilfelle.copy(
                fnr = ARBEIDSTAKER_FNR,
                virksomhetsnummer = VIRKSOMHETSNUMMER
        ))
        lagreOgHentMotebehovOgSendOversikthendelse(harBehov = true)
    }

    @Test
    fun getAsArbeidstakerbehovStatusAndSendOversikthendelseWithMotebehovHarBehovFalse() {
        oppfolgingstilfelleDAO.create(generateOversikthendelsetilfelle.copy(
                fnr = ARBEIDSTAKER_FNR,
                virksomhetsnummer = VIRKSOMHETSNUMMER
        ))
        lagreOgHentMotebehovOgSendOversikthendelse(harBehov = false)
    }

    private fun mockSTS() {
        if (!stsConsumer.isTokenCached()) {
            mockAndExpectSTSService(mockRestServiceServer, stsUrl, srvUsername, srvPassword)
        }
    }

    private fun lagreOgHentMotebehovOgSendOversikthendelse(harBehov: Boolean) {
        mockSTS()
        mockAndExpectBehandlendeEnhetRequest(mockRestServiceServer, behandlendeenhetUrl, ARBEIDSTAKER_FNR)

        val motebehovSvar = motebehovGenerator.lagMotebehovSvar(harBehov)

        motebehovArbeidstakerController.submitMotebehovArbeidstaker(motebehovSvar)
        val motebehovStatus: MotebehovStatus = motebehovArbeidstakerController.motebehovStatusArbeidstaker()

        Assert.assertTrue(motebehovStatus.visMotebehov)
        Assert.assertEquals(MotebehovSkjemaType.SVAR_BEHOV, motebehovStatus.skjemaType)
        val motebehov = motebehovStatus.motebehov!!
        Assert.assertNotNull(motebehov)
        Assertions.assertThat(motebehov.opprettetAv).isEqualTo(ARBEIDSTAKER_AKTORID)
        Assertions.assertThat(motebehov.arbeidstakerFnr).isEqualTo(ARBEIDSTAKER_FNR)
        Assertions.assertThat(motebehov.virksomhetsnummer).isEqualTo(VIRKSOMHETSNUMMER)
        Assertions.assertThat(motebehov.motebehovSvar).isEqualToComparingFieldByField(motebehovSvar)
        if (harBehov) {
            Mockito.verify(oversikthendelseProducer).sendOversikthendelse(any())
        } else {
            Mockito.verify(oversikthendelseProducer, Mockito.never()).sendOversikthendelse(any())
        }
    }

    private fun cleanDB() {
        motebehovDAO.nullstillMotebehov(ARBEIDSTAKER_AKTORID)
        oppfolgingstilfelleDAO.nullstillOppfolgingstilfeller(Fodselsnummer(ARBEIDSTAKER_FNR))
    }
}

private fun <T> any(): T {
    Mockito.any<T>()
    return uninitialized()
}

private fun <T> uninitialized(): T = null as T
