package io.github.respawnedguy.familyapp.data.offers

import io.github.respawnedguy.familyapp.data.offers.MarketValues.euroText
import io.github.respawnedguy.familyapp.data.offers.MarketValues.percent
import io.github.respawnedguy.familyapp.data.offers.MarketValues.title
import io.github.respawnedguy.familyapp.domain.offers.Chain
import io.github.respawnedguy.familyapp.domain.offers.Offer
import kotlinx.datetime.LocalDate

/**
 * Norma: server-rendered product boxes (`<article class="… produktBoxContainer">`) on the fresh
 * produce page and on the day pages ("ab Montag, 23.11.26") linked from /de/angebote/. Bundesweit.
 */
internal object NormaParsers {
    private val ARTICLE =
        Regex("""<article class="[^"]*produktBoxContainer".*?</article>""", RegexOption.DOT_MATCHES_ALL)
    private val AMOUNT = Regex("""class="produktBox-txt-inh">(.*?)</li>""")
    private val OLD_PRICE = Regex("""class="produktBox-cont-wrapper-uvp">(.*?)</li>""")
    private val SAVING = Regex("""class="produktBox-cont-wrapper-billiger"[^>]*>(.*?)</div>""")
    private val DAY_LINK = Regex("""href="(/de/angebote/ab-[a-z]+,-\d{2}\.\d{2}\.\d{2}/)"""")
    private val DAY = Regex("""(\d{2})\.(\d{2})\.(\d{2})/?$""")
    private const val CENTURY = 2000
    private const val MONTH = 2
    private const val YEAR = 3
    private const val MAX_DAYS = 6

    /** The day pages of the overview, oldest first ("/de/angebote/ab-montag,-23.11.26/"). */
    fun dayPages(html: String): List<String> =
        DAY_LINK.findAll(html).map { it.groupValues[1] }.distinct().take(MAX_DAYS).toList()

    /** First day of a day page from its path, or null. */
    fun dayOf(path: String): LocalDate? = DAY.find(path)?.groupValues?.let { g ->
        runCatching { LocalDate(CENTURY + g[YEAR].toInt(), g[MONTH].toInt(), g[1].toInt()) }.getOrNull()
    }

    fun offers(html: String, validFrom: LocalDate? = null): List<Offer> = ARTICLE.findAll(html).mapNotNull { m ->
        val box = m.value
        val name = MarketHtml.first(box, "h3")?.let(MarketHtml::text)?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null
        val amount = AMOUNT.find(box)?.groupValues?.get(1)
        val old = OLD_PRICE.find(box)?.groupValues?.get(1)
        val saving = SAVING.find(box)?.groupValues?.get(1)
        Offer(
            chain = Chain.NORMA,
            title = title(name, amount?.let(MarketHtml::text)),
            priceCents = MarketHtml.attribute(box, "aria-label")?.let(::euroText),
            oldPriceCents = old?.let { euroText(MarketHtml.text(it)) },
            savingPercent = saving?.let { percent(MarketHtml.text(it)) },
            validFrom = validFrom,
        )
    }.distinctBy { it.title }.toList()
}

/**
 * Netto (Stavenhagen, netto.de): server-rendered product cards (`aria-label="product-N"`) with the
 * name in `<h4>`, amount and base price in the following `<p>` and the price in `<h3>` ("0.<span>59</span>").
 */
internal object NettoParsers {
    private val CARD = Regex("""aria-label="product-\d+"""")
    private val OLD = Regex("""statt\s*(\d{1,4})\.(\d{2})""")
    private val PRICE = Regex("""^(\d{1,4})\.(\d{2})$""")
    private const val HUNDRED = 100
    private const val MAX_CARDS = 300

    fun offers(html: String): List<Offer> {
        val starts = CARD.findAll(html).map { it.range.last + 1 }.take(MAX_CARDS).toList()
        return starts.mapIndexedNotNull { i, start ->
            val card = html.substring(start, starts.getOrNull(i + 1) ?: html.length)
            val name = MarketHtml.first(card, "h4")?.let(MarketHtml::text)?.takeIf { it.isNotEmpty() }
                ?: return@mapIndexedNotNull null
            val nameEnd = card.indexOf("</h4>")
            val details = MarketHtml.first(card, "p", nameEnd)?.let(MarketHtml::text)
            val price = MarketHtml.first(card, "h3")?.let { MarketHtml.text(it).replace(" ", "") }
            Offer(
                chain = Chain.NETTO,
                title = title(name, details?.substringBefore(" 1 kg")?.substringBefore(" 1 Liter")),
                priceCents = price?.let(PRICE::find)?.let(::cents),
                oldPriceCents = OLD.find(MarketHtml.text(card))?.let(::cents),
            )
        }.distinctBy { it.title }
    }

    private fun cents(m: MatchResult) = m.groupValues[1].toInt() * HUNDRED + m.groupValues[2].toInt()
}
