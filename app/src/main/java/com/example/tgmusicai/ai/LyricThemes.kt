package com.example.tgmusicai.ai

/**
 * The themes a song's lyrics can be tagged with, and the descriptions used to recognise them.
 *
 * Tagging is zero-shot and costs no extra model: each description is embedded with the same
 * [LyricsEmbeddingEngine] that embeds the lyrics, and a song is tagged with the themes whose
 * description its lyrics sit closest to. Adding a theme here is all that adding a theme takes.
 *
 * This exists alongside the raw lyrics embedding rather than replacing it, because the two answer
 * different questions. Cosine similarity between two lyric embeddings blends everything at once --
 * vocabulary, register, imagery, subject -- so two songs can score highly for sounding alike on
 * the page while being about nothing in common. An explicit theme is a statement about what a song
 * is *about*, which is what makes "another song about class and work" a thing the engine can
 * actually find, rather than something it might stumble into.
 *
 * Descriptions are written as full phrases, not bare labels. A sentence-embedding model places
 * "solidarity, workers organising, class struggle and the fight against exploitation" somewhere
 * far more useful than it places the single word "socialism".
 */
internal object LyricThemes {

    /**
     * How far a theme must stand out from the rest, in standard deviations, to be tagged.
     *
     * Deliberately not an absolute cosine threshold. Sentence embeddings are anisotropic -- a few
     * vectors are close to almost any text -- so an absolute cut-off tags those hub themes on
     * everything while genuinely matching themes miss. Measuring each theme against the spread of
     * this song's own scores cancels that out, since a theme that is close to every song is close
     * to this one too and gains nothing by it.
     */
    const val STANDOUT_THRESHOLD = 1.6f

    /** Most themes kept per song, best first. A song is rarely meaningfully about more than this. */
    const val MAX_THEMES_PER_SONG = 5

    /**
     * Theme name to the description it is recognised by.
     *
     * Ordered loosely from the political and social through the personal to the circumstantial.
     * The social and political entries are the point of the exercise: they are the themes that
     * conventional recommenders miss entirely, because nothing about a song's sound or its genre
     * tag says it is about work, protest or money.
     */
    val DESCRIPTIONS: Map<String, String> = linkedMapOf(
        "class and labour" to
            "solidarity, workers organising, class struggle, unions, labour and the fight against exploitation by bosses and owners",
        "socialism and collective politics" to
            "socialism, communism, collective ownership, redistribution of wealth, the people against capital and the ruling class",
        "protest and resistance" to
            "protest, revolution, rising up, marching in the streets, resisting authority, refusing to obey unjust power",
        "inequality and poverty" to
            "poverty, hunger, rent, debt, struggling to survive, the gap between rich and poor, being ground down by the system",
        "war and conflict" to
            "soldiers in trenches, bombs and shells falling on villages, armies at the front line, a country at war",
        "racism and injustice" to
            "racism, discrimination, police violence, civil rights, injustice suffered because of who you are",
        "freedom and liberation" to
            "freedom, liberation, breaking chains, escaping oppression, the right to live as you choose",
        "environment and nature" to
            "the earth, nature, rivers and forests, climate, pollution, what we are doing to the planet",
        "religion and faith" to
            "God, prayer, faith, the church, the soul, salvation, heaven and judgement",
        "death and mortality" to
            "a funeral, mourning someone who has died, grief that does not lift, the shortness of life",
        "love and devotion" to
            "being in love, devotion, longing for someone, wanting to be with them, giving your heart away",
        "heartbreak and loss" to
            "they walked out and left me, the relationship is over, sleeping alone on my side of the bed, still not over them",
        "desire and sex" to
            "desire, attraction, bodies, wanting someone physically, the heat between two people",
        "loneliness and isolation" to
            "loneliness, being alone, feeling unseen, isolation, nobody to talk to, empty rooms",
        "mental health and despair" to
            "depression, anxiety, despair, not wanting to go on, the weight in your head, fighting your own mind",
        "hope and resilience" to
            "hope, holding on, getting back up, surviving what was done to you, better days ahead",
        "anger and defiance" to
            "rage, defiance, refusing to back down, hitting back, telling someone exactly what you think of them",
        "money and ambition" to
            "money, wealth, hustling, getting rich, ambition, chasing success and what it costs",
        "fame and excess" to
            "fame, celebrity, parties, luxury, excess, the emptiness behind the spotlight",
        "drugs and intoxication" to
            "drugs, drinking, getting high, addiction, numbing yourself, the comedown",
        "partying and dancing" to
            "the club at 2am, dancing until sunrise, strobe lights and drinks, letting go on a crowded dancefloor",
        "friendship and community" to
            "friends, crew, neighbourhood, the people who have your back, belonging somewhere",
        "family and childhood" to
            "family, mother and father, growing up, childhood memories, home and the people who raised you",
        "home and belonging" to
            "home, the town you come from, roots, leaving and returning, where you belong",
        "travel and escape" to
            "the road, driving away, leaving town, running, escaping to somewhere new",
        "city life" to
            "the city, streets, concrete, subways, the noise and grind of urban life",
        "nostalgia and memory" to
            "remembering, the past, how things used to be, old photographs, time passing",
        "identity and self" to
            "who I am, finding yourself, self-worth, becoming the person you are, standing in your own truth",
        "youth and growing up" to
            "being young, growing up, teenage years, school, the recklessness and confusion of youth",
        "work and daily grind" to
            "the job, clocking in, the daily grind, the commute, working for someone else's profit",
        "crime and the street" to
            "crime, the street, hustling illegally, prison, police, violence in the neighbourhood",
        "technology and modern life" to
            "phones, screens, the internet, machines, the strangeness of living now",
        "betrayal and mistrust" to
            "betrayal, lies, being used, fake friends, someone turning on you",
        "spiritual searching" to
            "searching for meaning, questioning existence, the universe, purpose and why we are here",
        "celebration and joy" to
            "joy, celebration, gratitude, happiness, feeling alive and good",
        "storytelling and character" to
            "a story about particular people, a narrative with characters and events, a tale being told",
    )
}
