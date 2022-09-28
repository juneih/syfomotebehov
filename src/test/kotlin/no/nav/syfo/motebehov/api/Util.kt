package no.nav.syfo.motebehov.api

import no.nav.syfo.consumer.aktorregister.domain.Fodselsnummer
import no.nav.syfo.oppfolgingstilfelle.database.OppfolgingstilfelleDAO
import no.nav.syfo.oppfolgingstilfelle.kafka.domain.KafkaOppfolgingstilfellePerson

fun dbCreateOppfolgingstilfelle(
    oppfolgingstilfelleDAO: OppfolgingstilfelleDAO,
    oppfolgingstilfellePerson: KafkaOppfolgingstilfellePerson
) {
    val fodselsnummer = Fodselsnummer(oppfolgingstilfellePerson.personIdentNumber)
    val oppfolgingstilfelle = oppfolgingstilfellePerson.oppfolgingstilfelleList.first()
    val virksomhetsnummer = oppfolgingstilfelle.virksomhetsnummerList.first()

    oppfolgingstilfelleDAO.create(
        fodselsnummer,
        oppfolgingstilfelle,
        virksomhetsnummer
    )
}
