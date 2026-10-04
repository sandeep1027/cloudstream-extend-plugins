package com.torrin.mdblist

/**
 * Curated dashboard titles. Every entry was verified against IMDB's public
 * suggestion API at build time (real ids, years, posters) — regenerate with
 * gen_lists.py + this snippet instead of editing by hand.
 */
data class CuratedItem(
    val name: String,
    val id: String,
    val year: Int?,
    val poster: String?,
    val type: String // "Movie" | "TvSeries"
)

object Curated {
    val MOVIES = listOf(
        CuratedItem("Interstellar", "tt0816692", 2014, "https://m.media-amazon.com/images/M/MV5BYzdjMDAxZGItMjI2My00ODA1LTlkNzItOWFjMDU5ZDJlYWY3XkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Inception", "tt1375666", 2010, "https://m.media-amazon.com/images/M/MV5BMjAxMzY3NjcxNF5BMl5BanBnXkFtZTcwNTI5OTM0Mw@@._V1_.jpg", "Movie"),
        CuratedItem("The Dark Knight Rises", "tt1345836", 2012, "https://m.media-amazon.com/images/M/MV5BMTk4ODQzNDY3Ml5BMl5BanBnXkFtZTcwODA0NTM4Nw@@._V1_.jpg", "Movie"),
        CuratedItem("The Matrix", "tt0133093", 1999, "https://m.media-amazon.com/images/M/MV5BN2NmN2VhMTQtMDNiOS00NDlhLTliMjgtODE2ZTY0ODQyNDRhXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("The Godfather", "tt0068646", 1972, "https://m.media-amazon.com/images/M/MV5BNGEwYjgwOGQtYjg5ZS00Njc1LTk2ZGEtM2QwZWQ2NjdhZTE5XkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Pulp Fiction", "tt0110912", 1994, "https://m.media-amazon.com/images/M/MV5BYTViYTE3ZGQtNDBlMC00ZTAyLTkyODMtZGRiZDg0MjA2YThkXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("The Shawshank Redemption", "tt0111161", 1994, "https://m.media-amazon.com/images/M/MV5BMDAyY2FhYjctNDc5OS00MDNlLThiMGUtY2UxYWVkNGY2ZjljXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Dune: Part Two", "tt15239678", 2024, "https://m.media-amazon.com/images/M/MV5BNTc0YmQxMjEtODI5MC00NjFiLTlkMWUtOGQ5NjFmYWUyZGJhXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Oppenheimer", "tt15398776", 2023, "https://m.media-amazon.com/images/M/MV5BN2JkMDc5MGQtZjg3YS00NmFiLWIyZmQtZTJmNTM5MjVmYTQ4XkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Avatar: The Way of Water", "tt1630029", 2022, "https://m.media-amazon.com/images/M/MV5BNWI0Y2NkOWEtMmM2OC00MjQ3LWI1YzItZGQxYzQ3NzI4NWZmXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Barbie", "tt1517268", 2023, "https://m.media-amazon.com/images/M/MV5BYjI3NDU0ZGYtYjA2YS00Y2RlLTgwZDAtYTE2YTM5ZjE1M2JlXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Top Gun: Maverick", "tt1745960", 2022, "https://m.media-amazon.com/images/M/MV5BMDBkZDNjMWEtOTdmMi00NmExLTg5MmMtNTFlYTJlNWY5YTdmXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Joker", "tt7286456", 2019, "https://m.media-amazon.com/images/M/MV5BNzY3OWQ5NDktNWQ2OC00ZjdlLThkMmItMDhhNDk3NTFiZGU4XkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Parasite", "tt6751668", 2019, "https://m.media-amazon.com/images/M/MV5BYjk1Y2U4MjQtY2ZiNS00OWQyLWI3MmYtZWUwNmRjYWRiNWNhXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Spider-Man: Across the Spider-Verse", "tt9362722", 2023, "https://m.media-amazon.com/images/M/MV5BNThiZjA3MjItZGY5Ni00ZmJhLWEwN2EtOTBlYTA4Y2E0M2ZmXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Everything Everywhere All at Once", "tt6710474", 2022, "https://m.media-amazon.com/images/M/MV5BOWNmMzAzZmQtNDQ1NC00Nzk5LTkyMmUtNGI2N2NkOWM4MzEyXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Whiplash", "tt2582802", 2014, "https://m.media-amazon.com/images/M/MV5BMDFjOWFkYzktYzhhMC00NmYyLTkwY2EtYjViMDhmNzg0OGFkXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("La La Land", "tt3783958", 2016, "https://m.media-amazon.com/images/M/MV5BMDllYjliOTUtMDJjZC00ODIzLWJmNGMtOWI2NzQxMjA2NzdlXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Fight Club", "tt0137523", 1999, "https://m.media-amazon.com/images/M/MV5BOTgyOGQ1NDItNGU3Ny00MjU3LTg2YWEtNmEyYjBiMjI1Y2M5XkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Forrest Gump", "tt0109830", 1994, "https://m.media-amazon.com/images/M/MV5BNDYwNzVjMTItZmU5YS00YjQ5LTljYjgtMjY2NDVmYWMyNWFmXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("The Batman", "tt1877830", 2022, "https://m.media-amazon.com/images/M/MV5BMmU5NGJlMzAtMGNmOC00YjJjLTgyMzUtNjAyYmE4Njg5YWMyXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Blade Runner 2049", "tt1856101", 2017, "https://m.media-amazon.com/images/M/MV5BNzA1Njg4NzYxOV5BMl5BanBnXkFtZTgwODk5NjU3MzI@._V1_.jpg", "Movie"),
        CuratedItem("Get Out", "tt5052448", 2017, "https://m.media-amazon.com/images/M/MV5BMjUxMDQwNjcyNl5BMl5BanBnXkFtZTgwNzcwMzc0MTI@._V1_.jpg", "Movie"),
        CuratedItem("Knives Out", "tt8946378", 2019, "https://m.media-amazon.com/images/M/MV5BZDU5ZTRkYmItZjg0Mi00ZTQwLThjMWItNWM3MTMxMzVjZmVjXkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("The Grand Budapest Hotel", "tt2278388", 2014, "https://m.media-amazon.com/images/M/MV5BMzM5NjUxOTEyMl5BMl5BanBnXkFtZTgwNjEyMDM0MDE@._V1_.jpg", "Movie"),
        CuratedItem("Mad Max: Fury Road", "tt1392190", 2015, "https://m.media-amazon.com/images/M/MV5BZDRkODJhOTgtOTc1OC00NTgzLTk4NjItNDgxZDY4YjlmNDY2XkEyXkFqcGc@._V1_.jpg", "Movie"),
        CuratedItem("Arrival", "tt2543164", 2016, "https://m.media-amazon.com/images/M/MV5BMTExMzU0ODcxNDheQTJeQWpwZ15BbWU4MDE1OTI4MzAy._V1_.jpg", "Movie"),
        CuratedItem("Dune", "tt1160419", 2021, "https://m.media-amazon.com/images/M/MV5BNWIyNmU5MGYtZDZmNi00ZjAwLWJlYjgtZTc0ZGIxMDE4ZGYwXkEyXkFqcGc@._V1_.jpg", "Movie"),
    )

    val TV = listOf(
        CuratedItem("Breaking Bad", "tt0903747", 2008, "https://m.media-amazon.com/images/M/MV5BMzU5ZGYzNmQtMTdhYy00OGRiLTg0NmQtYjVjNzliZTg1ZGE4XkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("Game of Thrones", "tt0944947", 2011, "https://m.media-amazon.com/images/M/MV5BNGYxOGJkMjItZjVkZC00OGEzLWExNjktOTZmNGZhZmRlMTk2XkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("The Last of Us", "tt3581920", 2023, "https://m.media-amazon.com/images/M/MV5BYWI3ODJlMzktY2U5NC00ZjdlLWE1MGItNWQxZDk3NWNjN2RhXkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("Wednesday", "tt13443470", 2022, "https://m.media-amazon.com/images/M/MV5BY2E1NDI5OWEtODJmYi00Nzg2LWI4MjUtODFiMTU2YWViOTU3XkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("Stranger Things", "tt4574334", 2016, "https://m.media-amazon.com/images/M/MV5BMjEzMDAxOTUyMV5BMl5BanBnXkFtZTgwNzAxMzYzOTE@._V1_.jpg", "TvSeries"),
        CuratedItem("The Bear", "tt14452776", 2022, "https://m.media-amazon.com/images/M/MV5BMjk2NWI5OTctODcwYy00NGRmLWFmN2YtOTZiNzFiYjVlODBkXkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("House of the Dragon", "tt11198330", 2022, "https://m.media-amazon.com/images/M/MV5BOWU2ZDA0M2EtNWEyYy00MWUwLWI4NjAtYzkxMDFjYzZkNGFiXkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("The Boys", "tt1190634", 2019, "https://m.media-amazon.com/images/M/MV5BZjU4OWNiYzQtMzc1NS00NjZlLTgyYTctZWY4ZmEzMTkxYjA4XkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("Succession", "tt7660850", 2018, "https://m.media-amazon.com/images/M/MV5BYTY4YTVkY2QtMjRmOS00YzliLWIxOWQtMTdkOTVkN2UzODNmXkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("The White Lotus", "tt13406094", 2021, "https://m.media-amazon.com/images/M/MV5BZmM1MGM0MDQtZTAzNy00ZGJkLWI4MDUtNjBmMzdhYjhlM2QwXkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("Severance", "tt11280740", 2022, "https://m.media-amazon.com/images/M/MV5BZDI5YzJhODQtMzQyNy00YWNmLWIxMjUtNDBjNjA5YWRjMzExXkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("Ted Lasso", "tt10986410", 2020, "https://m.media-amazon.com/images/M/MV5BM2ZlYTU0YWUtMzY4OC00N2JkLTk1ZGQtN2UxNWJhMTBkMzM4XkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("Fleabag", "tt5687612", 2016, "https://m.media-amazon.com/images/M/MV5BMjA4MzU5NzQxNV5BMl5BanBnXkFtZTgwOTg3MDA5NzM@._V1_.jpg", "TvSeries"),
        CuratedItem("Better Call Saul", "tt3032476", 2015, "https://m.media-amazon.com/images/M/MV5BMTAxOTQ0MjUzMzJeQTJeQWpwZ15BbWU4MDY0NTAxNzMx._V1_.jpg", "TvSeries"),
        CuratedItem("Peaky Blinders", "tt2442560", 2013, "https://m.media-amazon.com/images/M/MV5BOGM0NGY3ZmItOGE2ZC00OWIxLTk0N2EtZWY4Yzg3ZDlhNGI3XkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("Andor", "tt9253284", 2022, "https://m.media-amazon.com/images/M/MV5BNGI2MTJjMjUtMTJhOC00YTY2LTg1NjUtMTdmMjg4YTk2YjM5XkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("Squid Game", "tt10919420", 2021, "https://m.media-amazon.com/images/M/MV5BMDcxYjg1OTctYmUxZi00ZjhjLWIwNTMtNmI2M2Y4OTU1YWU2XkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("The Office", "tt0386676", 2005, "https://m.media-amazon.com/images/M/MV5BZjQwYzBlYzUtZjhhOS00ZDQ0LWE0NzAtYTk4MjgzZTNkZWEzXkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("Shōgun", "tt2788316", 2024, "https://m.media-amazon.com/images/M/MV5BZmJkMDRjYzEtMWI3Ny00OWE3LWJlNTItMGQ1MTQzMzc3NDY5XkEyXkFqcGc@._V1_.jpg", "TvSeries"),
        CuratedItem("Chernobyl", "tt7366338", 2019, "https://m.media-amazon.com/images/M/MV5BNzU0OTI4YTQtNGQ1ZS00ZjA4LTg3MTMtZjkyZWNjN2RiZDJmXkEyXkFqcGc@._V1_.jpg", "TvSeries"),
    )
}
