package de.glas.entdecker

/** Grammatisches Geschlecht, für "ein/eine" und "der/die/das". */
enum class Genus(val ein: String, val der: String) {
    M("ein", "Der"), F("eine", "Die"), N("ein", "Das")
}

enum class Gruppe(val titel: String, val farbe: Int) {
    TIER("Tiere", 0xFF4CAF50.toInt()),
    FAHRZEUG("Fahrzeuge", 0xFF2196F3.toInt()),
    DING("Dinge", 0xFFFF9800.toInt()),
    ESSEN("Essen", 0xFFE91E63.toInt()),
}

/**
 * Etwas, das der Entdecker kennt.
 * @param klassen Nummern der Bildklassen (ImageNet, 0..999), die dazu gehören
 * @param geraeusch Dateiname ohne Endung in assets/geraeusche
 * @param spruch was danach gesagt wird, z. B. "Der Hund macht Wau wau!"
 */
class Ding(
    val id: String,
    val name: String,
    val genus: Genus,
    val emoji: String,
    val gruppe: Gruppe,
    val geraeusch: String,
    val spruch: String,
    val klassen: IntArray,
) {
    val ansage get() = "Das ist ${genus.ein} $name!"
}

private fun r(a: Int, b: Int) = (a..b).toList()

object Katalog {
    private val liste = mutableListOf<Ding>()

    private fun tier(id: String, name: String, g: Genus, emoji: String, ger: String, spruch: String, vararg k: Any) =
        add(id, name, g, emoji, Gruppe.TIER, ger, spruch, k)

    private fun fahrzeug(id: String, name: String, g: Genus, emoji: String, ger: String, spruch: String, vararg k: Any) =
        add(id, name, g, emoji, Gruppe.FAHRZEUG, ger, spruch, k)

    private fun ding(id: String, name: String, g: Genus, emoji: String, ger: String, spruch: String, vararg k: Any) =
        add(id, name, g, emoji, Gruppe.DING, ger, spruch, k)

    private fun essen(id: String, name: String, g: Genus, emoji: String, spruch: String, vararg k: Any) =
        add(id, name, g, emoji, Gruppe.ESSEN, "mampf", spruch, k)

    private fun add(id: String, name: String, g: Genus, emoji: String, gr: Gruppe, ger: String, spruch: String, k: Array<out Any>) {
        val nums = k.flatMap { if (it is List<*>) it.map { x -> x as Int } else listOf(it as Int) }
        liste += Ding(id, name, g, emoji, gr, ger, spruch, nums.toIntArray())
    }

    init {
        // ---------- Tiere ----------
        tier("hund", "Hund", Genus.M, "🐶", "bellen", "Der Hund macht Wau wau!", r(151, 268))
        tier("wolf", "Wolf", Genus.M, "🐺", "heulen", "Der Wolf heult: Auuuuh!", r(269, 275))
        tier("hyaene", "Hyäne", Genus.F, "🐕", "affe", "Die Hyäne kichert: Hihihi!", 276)
        tier("fuchs", "Fuchs", Genus.M, "🦊", "fiepen", "Der Fuchs hat einen buschigen Schwanz.", r(277, 280))
        tier("katze", "Katze", Genus.F, "🐱", "miauen", "Die Katze macht Miau!", r(281, 285), 383)
        tier("loewe", "Löwe", Genus.M, "🦁", "bruellen", "Der Löwe brüllt: Roaar!", 291)
        tier("tiger", "Tiger", Genus.M, "🐯", "bruellen", "Der Tiger faucht: Grrr!", 292)
        tier("leopard", "Leopard", Genus.M, "🐆", "bruellen", "Der Leopard hat Flecken!", 286, 287, 288, 289, 290)
        tier("gepard", "Gepard", Genus.M, "🐆", "bruellen", "Der Gepard ist das schnellste Tier an Land!", 293)
        tier("baer", "Bär", Genus.M, "🐻", "brummen", "Der Bär brummt: Brumm brumm!", 294, 295, 297)
        tier("eisbaer", "Eisbär", Genus.M, "🐻‍❄️", "brummen", "Der Eisbär lebt im Schnee!", 296)
        tier("panda", "Panda", Genus.M, "🐼", "brummen", "Der Panda frisst Bambus!", 388, 387)
        tier("koala", "Koala", Genus.M, "🐨", "brummen", "Der Koala klettert auf Bäume!", 105)
        tier("elefant", "Elefant", Genus.M, "🐘", "troeten", "Der Elefant trötet: Töröö!", 101, 385, 386)
        tier("affe", "Affe", Genus.M, "🐒", "affe", "Der Affe macht U-u-a-a!", r(365, 382), 384)
        tier("pferd", "Pferd", Genus.N, "🐴", "wiehern", "Das Pferd wiehert: Hüüüh!", 339)
        tier("zebra", "Zebra", Genus.N, "🦓", "wiehern", "Das Zebra hat Streifen!", 340)
        tier("schwein", "Schwein", Genus.N, "🐷", "grunzen", "Das Schwein macht Oink oink!", 341, 342, 343)
        tier("kuh", "Kuh", Genus.F, "🐮", "muhen", "Die Kuh macht Muuuh!", 345, 346, 347)
        tier("schaf", "Schaf", Genus.N, "🐑", "maehen", "Das Schaf macht Mäh!", 348, 349, 911)
        tier("steinbock", "Steinbock", Genus.M, "🐐", "maehen", "Der Steinbock klettert auf Berge!", 350)
        tier("reh", "Antilope", Genus.F, "🦌", "hufe", "Die Antilope springt ganz weit!", 351, 352, 353)
        tier("kamel", "Kamel", Genus.N, "🐫", "hufe", "Das Kamel hat einen Höcker!", 354)
        tier("lama", "Lama", Genus.N, "🦙", "hufe", "Das Lama hat ein flauschiges Fell!", 355)
        tier("nilpferd", "Nilpferd", Genus.N, "🦛", "blubbern", "Das Nilpferd badet gern!", 344)
        tier("hase", "Hase", Genus.M, "🐰", "hoppeln", "Der Hase hoppelt: Hopp hopp!", 330, 331, 332)
        tier("hamster", "Hamster", Genus.M, "🐹", "fiepen", "Der Hamster macht Fiep fiep!", 333)
        tier("meerschweinchen", "Meerschweinchen", Genus.N, "🐹", "fiepen", "Das Meerschweinchen quiekt: Wiiik!", 338)
        tier("eichhoernchen", "Eichhörnchen", Genus.N, "🐿️", "fiepen", "Das Eichhörnchen sammelt Nüsse!", 335, 336)
        tier("igel", "Stachelschwein", Genus.N, "🦔", "fiepen", "Das Stachelschwein hat Stacheln!", 334, 102)
        tier("biber", "Biber", Genus.M, "🦫", "platschen", "Der Biber baut einen Damm!", 337)
        tier("otter", "Otter", Genus.M, "🦦", "platschen", "Der Otter schwimmt gern!", 360, 356, 357, 358, 359)
        tier("stinktier", "Stinktier", Genus.N, "🦨", "zischen", "Das Stinktier stinkt: Pfui!", 361)
        tier("dachs", "Dachs", Genus.M, "🦡", "fiepen", "Der Dachs gräbt Höhlen!", 362)
        tier("erdmaennchen", "Erdmännchen", Genus.N, "🦦", "fiepen", "Das Erdmännchen hält Wache!", 298, 299)
        tier("faultier", "Faultier", Genus.N, "🦥", "zauber", "Das Faultier ist gaaanz langsam!", 364, 363)
        tier("kaenguru", "Känguru", Genus.N, "🦘", "hoppeln", "Das Känguru hüpft: Hopp hopp!", 104, 106)
        tier("schnabeltier", "Schnabeltier", Genus.N, "🦆", "platschen", "Das Schnabeltier hat einen Entenschnabel!", 103)
        tier("huhn", "Huhn", Genus.N, "🐔", "gackern", "Das Huhn macht Gack gack!", 8, 80, 81, 82, 83, 85, 86)
        tier("hahn", "Hahn", Genus.M, "🐓", "kraehen", "Der Hahn kräht: Kikeriki!", 7)
        tier("ente", "Ente", Genus.F, "🦆", "quaken", "Die Ente macht Quak quak!", 97, 98)
        tier("gans", "Gans", Genus.F, "🪿", "schnattern", "Die Gans schnattert: Gack gack!", 99)
        tier("schwan", "Schwan", Genus.M, "🦢", "schnattern", "Der Schwan schwimmt auf dem See!", 100)
        tier("vogel", "Vogel", Genus.M, "🐦", "zwitschern", "Der Vogel zwitschert: Piep piep!", r(10, 20), 91, 92, 95, 135, 136, 137, r(139, 143))
        tier("papagei", "Papagei", Genus.M, "🦜", "papagei", "Der Papagei kann sprechen: Hallo!", r(87, 90))
        tier("tukan", "Tukan", Genus.M, "🦜", "papagei", "Der Tukan hat einen bunten Schnabel!", 96, 93)
        tier("kolibri", "Kolibri", Genus.M, "🐦", "summen", "Der Kolibri ist ganz klein!", 94)
        tier("eule", "Eule", Genus.F, "🦉", "eule", "Die Eule macht Huhu!", 24)
        tier("adler", "Adler", Genus.M, "🦅", "adler", "Der Adler fliegt hoch oben!", 21, 22, 23)
        tier("strauss", "Strauß", Genus.M, "🦤", "hufe", "Der Strauß ist der größte Vogel!", 9, 138)
        tier("pfau", "Pfau", Genus.M, "🦚", "papagei", "Der Pfau schlägt ein Rad!", 84)
        tier("storch", "Storch", Genus.M, "🪶", "klick", "Der Storch klappert mit dem Schnabel!", 127, 128, 129, 131, 132, 133, 134)
        tier("flamingo", "Flamingo", Genus.M, "🦩", "schnattern", "Der Flamingo steht auf einem Bein!", 130)
        tier("pelikan", "Pelikan", Genus.M, "🐦", "platschen", "Der Pelikan fängt Fische!", 144, 146)
        tier("pinguin", "Pinguin", Genus.M, "🐧", "schnattern", "Der Pinguin watschelt!", 145)
        tier("frosch", "Frosch", Genus.M, "🐸", "frosch", "Der Frosch macht Quak quak!", 30, 31, 32)
        tier("molch", "Molch", Genus.M, "🦎", "platschen", "Der Molch lebt am Wasser!", r(25, 29))
        tier("schildkroete", "Schildkröte", Genus.F, "🐢", "zauber", "Die Schildkröte trägt ihr Haus!", r(33, 37))
        tier("eidechse", "Eidechse", Genus.F, "🦎", "zischen", "Die Eidechse sonnt sich!", 38, 39, 41, 42, 43, 44, 45, 46, 48)
        tier("chamaeleon", "Chamäleon", Genus.N, "🦎", "zauber", "Das Chamäleon wechselt die Farbe!", 40, 47)
        tier("krokodil", "Krokodil", Genus.N, "🐊", "zischen", "Das Krokodil hat viele Zähne!", 49, 50)
        tier("schlange", "Schlange", Genus.F, "🐍", "zischen", "Die Schlange zischt: Ssssss!", r(52, 68))
        tier("dino", "Dinosaurier", Genus.M, "🦖", "bruellen", "Der Dinosaurier brüllt: Roaar!", 51)
        tier("fisch", "Fisch", Genus.M, "🐟", "blubbern", "Der Fisch macht Blubb blubb!", 0, 389, 390, 391, 392, 394, 395, 396, 397)
        tier("goldfisch", "Goldfisch", Genus.M, "🐠", "blubbern", "Der Goldfisch macht Blubb blubb!", 1)
        tier("clownfisch", "Clownfisch", Genus.M, "🐠", "blubbern", "Der Clownfisch wohnt in einer Anemone!", 393)
        tier("hai", "Hai", Genus.M, "🦈", "blubbern", "Der Hai schwimmt im Meer!", 2, 3, 4)
        tier("rochen", "Rochen", Genus.M, "🐟", "blubbern", "Der Rochen gleitet durchs Wasser!", 5, 6)
        tier("wal", "Wal", Genus.M, "🐋", "wal", "Der Wal singt im Meer!", 147, 148, 149)
        tier("robbe", "Robbe", Genus.F, "🦭", "robbe", "Die Robbe macht Ow ow!", 150)
        tier("qualle", "Qualle", Genus.F, "🪼", "blubbern", "Die Qualle ist durchsichtig!", 107, 108)
        tier("seestern", "Seestern", Genus.M, "⭐", "blubbern", "Der Seestern hat fünf Arme!", 327, 328, 329)
        tier("krabbe", "Krabbe", Genus.F, "🦀", "klick", "Die Krabbe läuft seitwärts!", 118, 119, 120, 121, 125, 126)
        tier("hummer", "Hummer", Genus.M, "🦞", "klick", "Der Hummer hat große Scheren!", 122, 123, 124)
        tier("schnecke", "Schnecke", Genus.F, "🐌", "zauber", "Die Schnecke kriecht ganz langsam!", 112, 113, 114, 115, 116, 117)
        tier("biene", "Biene", Genus.F, "🐝", "summen", "Die Biene summt: Summ summ!", 309, 410, 599)
        tier("fliege", "Fliege", Genus.F, "🪰", "summen", "Die Fliege brummt: Bsss!", 308)
        tier("marienkaefer", "Marienkäfer", Genus.M, "🐞", "summen", "Der Marienkäfer hat Punkte!", 301)
        tier("kaefer", "Käfer", Genus.M, "🪲", "summen", "Der Käfer krabbelt!", 300, r(302, 307))
        tier("ameise", "Ameise", Genus.F, "🐜", "zauber", "Die Ameise ist ganz stark!", 310)
        tier("heuschrecke", "Heuschrecke", Genus.F, "🦗", "zirpen", "Die Heuschrecke zirpt!", 311, 312, 313, 315, 316, 317)
        tier("libelle", "Libelle", Genus.F, "🪽", "summen", "Die Libelle fliegt übers Wasser!", 318, 319, 320)
        tier("schmetterling", "Schmetterling", Genus.M, "🦋", "zauber", "Der Schmetterling flattert!", r(321, 326))
        tier("spinne", "Spinne", Genus.F, "🕷️", "zauber", "Die Spinne webt ein Netz!", 70, r(72, 77), 815)
        tier("skorpion", "Skorpion", Genus.M, "🦂", "zischen", "Der Skorpion hat einen Stachel!", 71)
        tier("wurm", "Wurm", Genus.M, "🪱", "zauber", "Der Wurm kriecht durch die Erde!", 110, 111)

        // ---------- Fahrzeuge ----------
        fahrzeug("auto", "Auto", Genus.N, "🚗", "hupe", "Das Auto macht Tut tut!", 436, 511, 581, 609, 656, 661, 479, 475, 535)
        fahrzeug("taxi", "Taxi", Genus.N, "🚕", "hupe", "Das Taxi fährt dich nach Hause!", 468)
        fahrzeug("limousine", "Limousine", Genus.F, "🚘", "hupe", "Die Limousine ist ganz lang!", 627)
        fahrzeug("rennauto", "Rennauto", Genus.N, "🏎️", "rennauto", "Das Rennauto macht Brumm brumm!", 751, 817, 573)
        fahrzeug("pickup", "Pick-up", Genus.M, "🛻", "motor", "Der Pick-up hat eine Ladefläche!", 717)
        fahrzeug("feuerwehr", "Feuerwehrauto", Genus.N, "🚒", "martinshorn", "Das Feuerwehrauto macht Tatü tata!", 555)
        fahrzeug("polizei", "Polizeiauto", Genus.N, "🚓", "martinshorn", "Das Polizeiauto macht Tatü tata!", 734)
        fahrzeug("krankenwagen", "Krankenwagen", Genus.M, "🚑", "martinshorn", "Der Krankenwagen macht Tatü tata!", 407)
        fahrzeug("bus", "Bus", Genus.M, "🚌", "bus", "Der Bus hält an der Haltestelle!", 779, 654, 874)
        fahrzeug("lastwagen", "Lastwagen", Genus.M, "🚚", "lkw", "Der Lastwagen hupt: Tröööt!", 867, 675)
        fahrzeug("muellauto", "Müllauto", Genus.N, "🚛", "lkw", "Das Müllauto holt den Müll ab!", 569)
        fahrzeug("abschleppwagen", "Abschleppwagen", Genus.M, "🚚", "motor", "Der Abschleppwagen zieht Autos!", 864)
        fahrzeug("wohnmobil", "Wohnmobil", Genus.N, "🚐", "motor", "Mit dem Wohnmobil geht es in den Urlaub!", 757, 660)
        fahrzeug("traktor", "Traktor", Genus.M, "🚜", "traktor", "Der Traktor macht Tuck tuck tuck!", 866, 730)
        fahrzeug("maehdrescher", "Mähdrescher", Genus.M, "🚜", "traktor", "Der Mähdrescher erntet das Korn!", 595, 856)
        fahrzeug("kran", "Kran", Genus.M, "🏗️", "piepen", "Der Kran hebt schwere Sachen!", 517)
        fahrzeug("gabelstapler", "Gabelstapler", Genus.M, "🚜", "piepen", "Der Gabelstapler macht Piep piep!", 561)
        fahrzeug("schneepflug", "Schneepflug", Genus.M, "🚜", "motor", "Der Schneepflug schiebt den Schnee weg!", 803, 802)
        fahrzeug("dampflok", "Dampflok", Genus.F, "🚂", "dampflok", "Die Dampflok macht Tschu tschu!", 820)
        fahrzeug("zug", "Zug", Genus.M, "🚆", "zug", "Der Zug fährt über die Schienen!", 466, 547, 565, 705)
        fahrzeug("strassenbahn", "Straßenbahn", Genus.F, "🚋", "strassenbahn", "Die Straßenbahn macht Bim bim!", 829)
        fahrzeug("flugzeug", "Flugzeug", Genus.N, "✈️", "flugzeug", "Das Flugzeug fliegt hoch in den Himmel!", 404, 895, 908)
        fahrzeug("zeppelin", "Zeppelin", Genus.M, "🎈", "propeller", "Der Zeppelin schwebt am Himmel!", 405)
        fahrzeug("rakete", "Rakete", Genus.F, "🚀", "rakete", "Drei, zwei, eins, die Rakete startet!", 812, 657)
        fahrzeug("fallschirm", "Fallschirm", Genus.M, "🪂", "wind", "Mit dem Fallschirm schwebt man runter!", 701)
        fahrzeug("schiff", "Schiff", Genus.N, "🚢", "schiff", "Das Schiff tutet: Tuuut!", 510, 628, 403, 554, 625, 913)
        fahrzeug("segelboot", "Segelboot", Genus.N, "⛵", "wind", "Der Wind bläst ins Segel!", 780, 914, 484, 871)
        fahrzeug("motorboot", "Motorboot", Genus.N, "🚤", "motorboot", "Das Motorboot ist schnell!", 814)
        fahrzeug("kanu", "Kanu", Genus.N, "🛶", "platschen", "Im Kanu paddelt man: Platsch platsch!", 472, 693, 576)
        fahrzeug("uboot", "U-Boot", Genus.N, "🫧", "sonar", "Das U-Boot taucht ganz tief!", 833)
        fahrzeug("fahrrad", "Fahrrad", Genus.N, "🚲", "klingel", "Das Fahrrad klingelt: Kling kling!", 444, 671)
        fahrzeug("dreirad", "Dreirad", Genus.N, "🚲", "klingel", "Das Dreirad hat drei Räder!", 870)
        fahrzeug("einrad", "Einrad", Genus.N, "🚲", "zauber", "Das Einrad hat nur ein Rad!", 880)
        fahrzeug("roller", "Roller", Genus.M, "🛵", "moped", "Der Roller macht Knatter knatter!", 665, 670)
        fahrzeug("golfwagen", "Golfwagen", Genus.M, "🛺", "motor", "Der Golfwagen fährt übers Gras!", 575)
        fahrzeug("rikscha", "Rikscha", Genus.F, "🛺", "klingel", "Die Rikscha wird gezogen!", 612)
        fahrzeug("kutsche", "Kutsche", Genus.F, "🐎", "hufe", "Die Pferde ziehen die Kutsche: Klipp klapp!", 603, 690)
        fahrzeug("schlitten", "Schlitten", Genus.M, "🛷", "wind", "Mit dem Schlitten geht es den Berg runter!", 450, 537)
        fahrzeug("einkaufswagen", "Einkaufswagen", Genus.M, "🛒", "rollen", "Ab in den Einkaufswagen!", 791)
        fahrzeug("kinderwagen", "Kinderwagen", Genus.M, "👶", "rollen", "Im Kinderwagen schläft das Baby!", 428)

        // ---------- Dinge ----------
        ding("uhr", "Uhr", Genus.F, "🕰️", "ticktack", "Die Uhr macht Tick tack!", 409, 892, 604, 835)
        ding("digitaluhr", "Digitaluhr", Genus.F, "⌚", "digitaluhr", "Die Uhr piept: Piep piep!", 530, 531, 826)
        ding("handy", "Handy", Genus.N, "📱", "handy", "Das Handy klingelt!", 487, 590)
        ding("telefon", "Telefon", Genus.N, "☎️", "telefon", "Das Telefon klingelt: Ring ring!", 528, 707)
        ding("klavier", "Klavier", Genus.N, "🎹", "klavier", "Das Klavier spielt Musik!", 579, 881)
        ding("orgel", "Orgel", Genus.F, "🎹", "orgel", "Die Orgel klingt ganz laut!", 687)
        ding("gitarre", "Gitarre", Genus.F, "🎸", "gitarre", "Die Gitarre macht Schrumm schrumm!", 402, 546, 420)
        ding("geige", "Geige", Genus.F, "🎻", "geige", "Die Geige singt!", 889, 486)
        ding("trommel", "Trommel", Genus.F, "🥁", "trommel", "Die Trommel macht Bumm bumm!", 541, 822, 542)
        ding("trompete", "Trompete", Genus.F, "🎺", "trompete", "Die Trompete macht Täterätä!", 513, 566, 875)
        ding("saxophon", "Saxophon", Genus.N, "🎷", "saxophon", "Das Saxophon spielt Jazz!", 776, 432, 683)
        ding("floete", "Flöte", Genus.F, "🪈", "floete", "Die Flöte macht Tü tü tü!", 558, 684, 699)
        ding("harfe", "Harfe", Genus.F, "🎶", "harfe", "Die Harfe klingt wie Zauberei!", 594)
        ding("xylophon", "Xylophon", Genus.N, "🎶", "xylophon", "Das Xylophon macht Pling pling!", 642)
        ding("rassel", "Rassel", Genus.F, "🪇", "rassel", "Die Rassel macht Rassel rassel!", 641)
        ding("gong", "Gong", Genus.M, "🔔", "gong", "Der Gong macht Gooong!", 577)
        ding("glocke", "Glocke", Genus.F, "🔔", "windspiel", "Die Glocke klingt: Ding dong!", 494, 442)
        ding("akkordeon", "Akkordeon", Genus.N, "🪗", "akkordeon", "Das Akkordeon spielt ein Lied!", 401)
        ding("mundharmonika", "Mundharmonika", Genus.F, "🎶", "mundharmonika", "In die Mundharmonika pustet man!", 593)
        ding("pfeife", "Trillerpfeife", Genus.F, "📯", "pfeife", "Die Pfeife macht Trillerpfiff!", 902)
        ding("mikrofon", "Mikrofon", Genus.N, "🎤", "radio", "Ins Mikrofon kann man singen!", 650)
        ding("lautsprecher", "Lautsprecher", Genus.M, "🔊", "radio", "Aus dem Lautsprecher kommt Musik!", 632, 598)
        ding("radio", "Radio", Genus.N, "📻", "radio", "Das Radio spielt Musik!", 754, 482, 485, 848, 605)
        ding("fernseher", "Fernseher", Genus.M, "📺", "fernseher", "Im Fernseher laufen Filme!", 851, 664, 782, 548)
        ding("computer", "Computer", Genus.M, "💻", "tippen", "Am Computer tippt man: Klack klack!", 527, 620, 508, 878, 810)
        ding("maus", "Computermaus", Genus.F, "🖱️", "klick", "Die Maus macht Klick klick!", 673)
        ding("fernbedienung", "Fernbedienung", Genus.F, "📺", "klick", "Mit der Fernbedienung schaltet man um!", 761, 613)
        ding("kamera", "Kamera", Genus.F, "📷", "kamera", "Die Kamera macht Klick! Ein Foto!", 759, 732)
        ding("drucker", "Drucker", Genus.M, "🖨️", "rollen", "Der Drucker druckt Bilder!", 742, 713)
        ding("staubsauger", "Staubsauger", Genus.M, "🧹", "staubsauger", "Der Staubsauger macht Brrrrr!", 882)
        ding("besen", "Besen", Genus.M, "🧹", "wind", "Mit dem Besen fegt man!", 462)
        ding("waschmaschine", "Waschmaschine", Genus.F, "🫧", "waschmaschine", "Die Waschmaschine wäscht die Wäsche!", 897, 534)
        ding("ventilator", "Ventilator", Genus.M, "🌀", "ventilator", "Der Ventilator macht Wind!", 545)
        ding("foehn", "Föhn", Genus.M, "💨", "ventilator", "Der Föhn pustet warme Luft!", 589)
        ding("mikrowelle", "Mikrowelle", Genus.F, "🍲", "mikrowelle", "Die Mikrowelle macht Piep!", 651)
        ding("toaster", "Toaster", Genus.M, "🍞", "toaster", "Der Toaster macht Toast!", 859)
        ding("kuehlschrank", "Kühlschrank", Genus.M, "🧊", "waschmaschine", "Im Kühlschrank bleibt alles kalt!", 760)
        ding("herd", "Herd", Genus.M, "🍳", "platschen", "Auf dem Herd wird gekocht!", 827, 567, 909, 469)
        ding("teekanne", "Teekanne", Genus.F, "🫖", "wasserkessel", "Der Tee ist heiß!", 849, 505, 550)
        ding("buegeleisen", "Bügeleisen", Genus.N, "👕", "zischen", "Das Bügeleisen zischt: Pssst!", 606)
        ding("bohrmaschine", "Bohrmaschine", Genus.F, "🔧", "bohrer", "Die Bohrmaschine macht Brrrrr!", 740)
        ding("hammer", "Hammer", Genus.M, "🔨", "hammer", "Der Hammer macht Klopf klopf!", 587, 596)
        ding("saege", "Kettensäge", Genus.F, "🪚", "rasenmaeher", "Die Säge macht Ritsch ratsch!", 491)
        ding("schraubenzieher", "Schraubenzieher", Genus.M, "🪛", "klick", "Mit dem Schraubenzieher dreht man Schrauben!", 784, 783)
        ding("schaufel", "Schaufel", Genus.F, "🪏", "schritte", "Mit der Schaufel buddelt man!", 792)
        ding("rasenmaeher", "Rasenmäher", Genus.M, "🌱", "rasenmaeher", "Der Rasenmäher mäht das Gras!", 621)
        ding("fussball", "Fußball", Genus.M, "⚽", "ball", "Den Ball kann man schießen: Tor!", 805)
        ding("basketball", "Basketball", Genus.M, "🏀", "ball", "Der Ball hüpft: Boing boing!", 430, 890, 768)
        ding("tennisball", "Tennisball", Genus.M, "🎾", "tischtennis", "Der Tennisball ist gelb!", 852, 429, 574, 522)
        ding("tischtennis", "Tischtennisball", Genus.M, "🏓", "tischtennis", "Ping pong, ping pong!", 722, 752)
        ding("luftballon", "Luftballon", Genus.M, "🎈", "ballon", "Der Luftballon macht Peng!", 417)
        ding("teddy", "Teddybär", Genus.M, "🧸", "spieluhr", "Der Teddy ist kuschelig!", 850)
        ding("puzzle", "Puzzle", Genus.N, "🧩", "klick", "Das Puzzle hat viele Teile!", 611)
        ding("windrad", "Windrad", Genus.N, "🌬️", "wind", "Das Windrad dreht sich im Wind!", 723)
        ding("schaukel", "Schaukel", Genus.F, "🛝", "quietschen", "Auf der Schaukel geht es hoch hinaus!", 843)
        ding("sparschwein", "Sparschwein", Genus.N, "🐷", "rassel", "Im Sparschwein klimpert das Geld!", 719)
        ding("regenschirm", "Regenschirm", Genus.M, "☂️", "regen", "Der Regen macht Plitsch platsch!", 879)
        ding("kerze", "Kerze", Genus.F, "🕯️", "pusten", "Die Kerze leuchtet! Pusten!", 470, 862)
        ding("schuh", "Schuh", Genus.M, "👟", "schritte", "Mit den Schuhen geht man: Tapp tapp!", 770, 774, 502, 514, 630)
        ding("socke", "Socke", Genus.F, "🧦", "zauber", "Die Socke wärmt den Fuß!", 806, 496)
        ding("muetze", "Hut", Genus.M, "🎩", "zauber", "Den Hut setzt man auf den Kopf!", 515, 808, 452)
        ding("brille", "Brille", Genus.F, "👓", "zauber", "Mit der Brille sieht man besser!", 836, 837)
        ding("rucksack", "Rucksack", Genus.M, "🎒", "zauber", "Den Rucksack trägt man auf dem Rücken!", 414, 748)
        ding("buch", "Buch", Genus.N, "📚", "blaettern", "Im Buch gibt es Geschichten!", 921, 917, 453, 624)
        ding("stift", "Stift", Genus.M, "✏️", "tippen", "Mit dem Stift kann man malen!", 418, 563, 709, 710)
        ding("schere", "Schere", Genus.F, "✂️", "klick", "Die Schere macht Schnipp schnapp!", 772)
        ding("tasse", "Tasse", Genus.F, "☕", "klirren", "Aus der Tasse trinkt man!", 504, 968)
        ding("glas", "Glas", Genus.N, "🥛", "klirren", "Das Glas macht Kling!", 441, 572)
        ding("flasche", "Flasche", Genus.F, "🍼", "platschen", "In der Flasche ist etwas zu trinken!", 898, 440, 907, 737, 720)
        ding("teller", "Teller", Genus.M, "🍽️", "klirren", "Auf dem Teller liegt das Essen!", 923, 809, 659)
        ding("loeffel", "Löffel", Genus.M, "🥄", "klirren", "Mit dem Löffel isst man Suppe!", 910, 618)
        ding("eimer", "Eimer", Genus.M, "🪣", "platschen", "Im Eimer ist Wasser!", 463, 756)
        ding("muelleimer", "Mülleimer", Genus.M, "🗑️", "klirren", "Der Müll kommt in den Mülleimer!", 412)
        ding("badewanne", "Badewanne", Genus.F, "🛁", "platschen", "In der Badewanne wird gebadet!", 435, 876, 896)
        ding("toilette", "Toilette", Genus.F, "🚽", "platschen", "Spülen nicht vergessen!", 861, 999)
        ding("stuhl", "Stuhl", Genus.M, "🪑", "quietschen", "Auf dem Stuhl kann man sitzen!", 559, 765, 423, 857)
        ding("sofa", "Sofa", Genus.N, "🛋️", "zauber", "Auf dem Sofa kuschelt man!", 831)
        ding("bett", "Bett", Genus.N, "🛏️", "spieluhr", "Im Bett schläft man: Gute Nacht!", 564, 520, 516, 431, 721, 750)
        ding("lampe", "Lampe", Genus.F, "💡", "klick", "Die Lampe macht Licht!", 846, 619, 818)
        ding("tisch", "Tisch", Genus.M, "🪑", "klirren", "Am Tisch wird gegessen!", 532, 526)
        ding("schrank", "Schrank", Genus.M, "🚪", "quietschen", "Im Schrank sind die Sachen!", 894, 493, 495)
        ding("schluessel", "Schloss", Genus.N, "🔒", "klick", "Das Schloss macht Klick!", 695, 507)
        ding("ampel", "Ampel", Genus.F, "🚦", "ampel", "Bei Rot stehen, bei Grün gehen!", 920)
        ding("verkehrsschild", "Verkehrsschild", Genus.N, "🛑", "hupe", "Das Schild zeigt den Weg!", 919)
        ding("briefkasten", "Briefkasten", Genus.M, "📫", "klick", "Im Briefkasten ist Post!", 637, 549)
        ding("zelt", "Zelt", Genus.N, "⛺", "wind", "Im Zelt schläft man draußen!", 672, 915)
        ding("haus", "Haus", Genus.N, "🏠", "schritte", "Im Haus wohnt man!", 425, 858, 853, 448)
        ding("schloss", "Burg", Genus.F, "🏰", "trompete", "In der Burg wohnt der König!", 483, 698)
        ding("kirche", "Kirche", Genus.F, "⛪", "windspiel", "Die Glocken läuten: Bimm bamm!", 497, 538)
        ding("bruecke", "Brücke", Genus.F, "🌉", "zug", "Über die Brücke geht es ans andere Ufer!", 821, 839, 888)
        ding("leuchtturm", "Leuchtturm", Genus.M, "🗼", "schiff", "Der Leuchtturm zeigt den Schiffen den Weg!", 437)
        ding("brunnen", "Brunnen", Genus.M, "⛲", "platschen", "Im Brunnen plätschert das Wasser!", 562)
        ding("karussell", "Karussell", Genus.N, "🎠", "spieluhr", "Das Karussell dreht sich!", 476)
        ding("blume", "Blume", Genus.F, "🌼", "zwitschern", "Die Blume riecht gut!", 985, 986, 883)
        ding("pilz", "Pilz", Genus.M, "🍄", "zauber", "Den Pilz nicht essen!", 947, r(991, 997))
        ding("vulkan", "Vulkan", Genus.M, "🌋", "rakete", "Der Vulkan spuckt Feuer!", 980, 974)
        ding("meer", "Meer", Genus.N, "🌊", "wind", "Am Meer gibt es Wellen!", 978, 977, 973, 976)
        ding("berg", "Berg", Genus.M, "⛰️", "wind", "Der Berg ist ganz hoch!", 970, 979, 972)

        // ---------- Essen ----------
        essen("banane", "Banane", Genus.F, "🍌", "Die Banane ist gelb! Mampf mampf!", 954)
        essen("apfel", "Apfel", Genus.M, "🍏", "Der Apfel ist knackig! Mampf mampf!", 948)
        essen("orange", "Orange", Genus.F, "🍊", "Die Orange ist saftig!", 950)
        essen("zitrone", "Zitrone", Genus.F, "🍋", "Die Zitrone ist sauer!", 951)
        essen("erdbeere", "Erdbeere", Genus.F, "🍓", "Die Erdbeere ist süß! Lecker!", 949)
        essen("ananas", "Ananas", Genus.F, "🍍", "Die Ananas hat eine Krone!", 953)
        essen("granatapfel", "Granatapfel", Genus.M, "🍎", "Im Granatapfel sind viele Kerne!", 957)
        essen("feige", "Feige", Genus.F, "🍇", "Die Feige ist süß!", 952, 955, 956)
        essen("brokkoli", "Brokkoli", Genus.M, "🥦", "Der Brokkoli sieht aus wie ein kleiner Baum!", 937, 938)
        essen("kohl", "Kohl", Genus.M, "🥬", "Der Kohl ist ganz rund!", 936)
        essen("gurke", "Gurke", Genus.F, "🥒", "Die Gurke ist grün!", 943, 939)
        essen("paprika", "Paprika", Genus.F, "🫑", "Die Paprika ist knackig!", 945)
        essen("kuerbis", "Kürbis", Genus.M, "🎃", "Der Kürbis ist groß und rund!", 940, 941, 942, 607)
        essen("mais", "Mais", Genus.M, "🌽", "Aus Mais macht man Popcorn!", 987)
        essen("pizza", "Pizza", Genus.F, "🍕", "Die Pizza ist lecker!", 963)
        essen("eis", "Eis", Genus.N, "🍦", "Das Eis ist kalt und süß!", 928, 929)
        essen("burger", "Hamburger", Genus.M, "🍔", "Der Hamburger ist lecker!", 933)
        essen("hotdog", "Hotdog", Genus.M, "🌭", "Im Hotdog ist ein Würstchen!", 934)
        essen("brot", "Brot", Genus.N, "🥖", "Das Brot ist frisch!", 930, 415)
        essen("brezel", "Brezel", Genus.F, "🥨", "Die Brezel ist salzig!", 932)
        essen("bagel", "Bagel", Genus.M, "🥯", "Der Bagel hat ein Loch!", 931)
        essen("nudeln", "Portion Nudeln", Genus.F, "🍝", "Nudeln sind lecker!", 959)
        essen("kuchen", "Kuchen", Genus.M, "🍰", "Der Kuchen ist süß!", 927, 509)
        essen("schokolade", "Schokolade", Genus.F, "🍫", "Schokolade ist süß!", 960)
    }

    val alle: List<Ding> = liste

    /** Für jede Bildklasse (0..999) das passende Ding, sonst null. */
    val zuKlasse: Array<Ding?> = arrayOfNulls<Ding>(1000).also { arr ->
        for (d in liste) for (k in d.klassen) if (arr[k] == null) arr[k] = d
    }

    fun byId(id: String) = liste.firstOrNull { it.id == id }
}
