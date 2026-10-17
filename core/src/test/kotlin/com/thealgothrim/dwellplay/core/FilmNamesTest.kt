package com.thealgothrim.dwellplay.core

import com.thealgothrim.dwellplay.core.films.FilmNames
import com.thealgothrim.dwellplay.core.films.FilmQuery
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class FilmNamesTest {
  @Test
  fun yearInBrackets_endsTheTitle() {
    assertEquals(FilmQuery("Spider-Man Brand New Day", 2026), FilmNames.parse("Spider-Man Brand New Day (2026) 1080p Dual Audio.mkv"))
  }

  @Test
  fun dottedReleaseNames() {
    assertEquals(FilmQuery("The Batman", 2022), FilmNames.parse("The.Batman.2022.1080p.WEB-DL.x264.mkv"))
  }

  @Test
  fun noYear_stopsAtTheFirstTag() {
    assertEquals(FilmQuery("Dune Part Two", null), FilmNames.parse("Dune Part Two 2160p HDR.mp4"))
  }

  @Test
  fun clipsKeepTheirWholeName() {
    // No year and no tag before "Final Battle": the clip's own name, which TMDB won't match exactly.
    val q = FilmNames.parse("Spider-Man No Way Home - Final Battle 4K IMAX.mp4")
    assertEquals("Spider-Man No Way Home - Final Battle", q?.title)
    assertFalse(FilmNames.matches(q!!, "Spider-Man: No Way Home", 2021))
  }

  @Test
  fun episodesAreNotFilms() {
    assertNull(FilmNames.parse("Stranger.Things.S05E01.1080p.mkv"))
  }

  @Test
  fun punctuationAndYearSlack() {
    val q = FilmQuery("Spider-Man Brand New Day", 2026)
    assertTrue(FilmNames.matches(q, "Spider-Man: Brand New Day", 2026))
    assertTrue(FilmNames.matches(q, "Spider-Man: Brand New Day", 2025))
    assertFalse(FilmNames.matches(q, "Spider-Man: Brand New Day", 2019))
    assertFalse(FilmNames.matches(q, "Spider-Man: Homecoming", 2026))
  }

  @Test
  fun clipsFindTheirFilm() {
    val nwh = FilmNames.parse("Spider-Man No Way Home - Final Battle 4K IMAX.mp4")!!
    assertTrue(FilmNames.clipOf(nwh, "Spider-Man: No Way Home", 2021))
    assertFalse(FilmNames.clipOf(nwh, "Spider-Man: Far From Home", 2019))
    val endgame = FilmNames.parse("Avengers Assemble - Endgame Final Battle 4K.mp4")!!
    assertTrue(FilmNames.clipOf(endgame, "Avengers: Endgame", 2019))
    assertFalse(FilmNames.clipOf(endgame, "The Avengers", 2012))
    // One-word titles need a matching year.
    val thor = FilmNames.parse("Thor Arrives in Wakanda - Bring Me Thanos (2018) 4K IMAX.mp4")!!
    assertFalse(FilmNames.clipOf(thor, "Thor", 2011))
  }
}
