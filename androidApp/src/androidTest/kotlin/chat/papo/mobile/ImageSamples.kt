package chat.papo.mobile

import java.util.Base64

/**
 * Imagens pequenas (64x64) para o `ImageDecodingTest`. Os três quadros trazem as cores vermelho, amarelo e verde, nessa ordem
 * (o primeiro é o vermelho). Geradas com Pillow (GIF, WebP e PNG parados, JPEG) e ffmpeg (WebP e APNG animados): os quadros
 * são f0.png, f1.png e f2.png, um disco colorido sobre fundo transparente, e o comando do ffmpeg usa `-framerate 4 -i f%d.png`.
 */
object ImageSamples {
    private fun decode(vararg parts: String): ByteArray = Base64.getDecoder().decode(parts.joinToString(""))

    /** GIF de 3 quadros (disco vermelho, amarelo e verde), 64x64, laço infinito. */
    val animatedGif: ByteArray get() = decode(
        "R0lGODlhQABAAIEAAAAAAP9GRgAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQJGQAAACwAAAAAQABAAAAI2AABCBxIsKDBgwgT",
        "KlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyARBhhJkmTIiSVTqjzZUKXLlSwPvpwJM6ZAmjhL2gSQs+fImD6DngxKFCTRox6PKu2o",
        "FOnGpks1QnWKcWrUi1apWsxaFCvXrhW/gqUoVujWsj29osWpdu3Mtm5dwo2bci5dk3bpZrxb8yxfvFX/BngqmONfpnyT6v0Ydyha",
        "oGJ3ft150yplgk0vy/SpeeHbzqBDix5NujRlwVM3o76KeTXrga5fV45ttiDtsbBvczbNu3fogAAh+QQJGQAAACwAAAAAQABAAIEA",
        "AAD/yCgAAAAAAAAI2AABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyAhBhhJkmTIiSVTqjzZUKXLlSwPvpwJM6ZA",
        "mjhL2gSQs+fImD6DngxKFCTRox6PKu2oFOnGpks1QnWKcWrUi1apWsxaFCvXrhW/gqUoVujWsj29osWpdu3Mtm5dwo2bci5dk3bp",
        "Zrxb8yxfvFX/BngqmONfpnyT6v0YdyhaoGJ3ft150yplgk0vy/SpeeHbzqBDix5NujRjwVA3o76KeTXrga5fV45ttiDtsbBvczbN",
        "u3fogAAh+QQJGQAAACwAAAAAQABAAIEAAAA8yHgAAAAAAAAI2AABCBxIsKDBgwgTKlzIsKHDhxAjSpxIsaLFixgzatzIsaPHjyAx",
        "BhhJkmTIiSVTqjzZUKXLlSwPvpwJM6ZAmjhL2gSQs+fImD6DngxKFCTRox6PKu2oFOnGpks1QnUqcipVi1avVsxa9CLXrli/+qwq",
        "NifZsjTPon2pdm3NrW5nto1r0itdlxnvvoWrt67dvj/zAn46mPBdpnqT0jXqdihaoGJ3ft150yplgk0vyxyrWaHczqBDix5NuvRf",
        "wFELooa6ebXWyq7BYo4teyDt2rBvmzXNu3fogAA7"
    )

    /** GIF de um quadro só (o disco vermelho), 64x64. */
    val singleFrameGif: ByteArray get() = decode(
        "R0lGODlhQABAAIEAAAAAAP9GRgAAAAAAACH5BAEAAAAALAAAAABAAEAAQAjYAAEIHEiwoMGDCBMqXMhQYYCHECE2nEgRQMSLGDNq",
        "lFjR4caPID92NBiypMmHIxOeXIkypcuBG19OZEmzJkeZOHMytHlRJ0KeK3UCrSl0aEmfB40GQMq0aVKlUHs6nUq1Kk6iVgtGbel0",
        "a0akXkVeDSv2JdmyZs9iBKt26VSvWbXSjEs3bVueT+/arMu3r9+/gAML3mlS8FC+UbOSfduWqV63OR9zlSkZsl3JYytnxky5smWX",
        "miNzFn3XceOuYRVDRQzUcOHBsGPLruqZZd7aR0nifj27N9OAADs="
    )

    /** WebP animado (VP8X com o bit de animação) de 3 quadros, 64x64. */
    val animatedWebp: ByteArray get() = decode(
        "UklGRt4BAABXRUJQVlA4WAoAAAASAAAAPwAAPwAAQU5JTQYAAAD/////AABBTk1GjAAAAAIAAAQAADgAADQAAPoAAAJWUDhMcwAA",
        "AC84AA0QDzBG8z9G8x/wcBNJkiP1a3MhDJSDdgvtoCyEMV+X0fP6AUT0fwKkDJV3ig4bG9AsIewIk11htjtgACup2+aZxQ7PDGvv",
        "SIt/MKy9Y7HDM902z8hWku6AJF1htiNMlhA2oFmHjemOyozqW6lX3wcAQU5NRowAAAACAAAEAAA4AAA0AAD6AAACVlA4THMAAAAv",
        "OAANEA8wyPM/KPMf8HATSZIj9WtzIQyUg3YL7aAshDFfl9Hz+gFE9H8CJGWovFN02NiAZglhR5jsCrPdAQNYSd02zyx2eGZYe0da",
        "/INh7R2LHZ7ptnlGtpJ0ByTpCrMdYbKEsAHNOmxMd1RmVF9Kvfo+AEFOTUaKAAAAAgAABAAAOwAANAAA+gAAAlZQOExyAAAALzsA",
        "DRAPMMjDM3jzH/BwFEmS4/QZSc+FsFAO2i20g7IQ5ilfjx55AYjo/wRIUlSld5IGk3UoFlDtCItdYbU7YACD1Gx6ZrPDM93KO+Jv",
        "9Ldtdnim2fSMbJB0ByTpCqsdYbGAah2KNZhMd5RGzb6TfPg+"
    )

    /** WebP sem animação (o disco vermelho), 64x64. */
    val stillWebp: ByteArray get() = decode(
        "UklGRoYAAABXRUJQVlA4THkAAAAvP8APEA8wRvM/RvMf8IAU27acatEPIyFSIg2kISUSMgR+s2f0AiL6PwG+YSswN4BLE/gvUK1w",
        "qjY4VwfM1YClGnGpprt+q+Nd/1VPqWr1Df6rPOO3Ot51qaa7lmrEXA04Vwecqg1UK/gvcGkCcwPQyjsvVTW+n6cDAA=="
    )

    /** APNG (chunk acTL) de 3 quadros, 64x64, que o servidor guardaria como PNG. */
    val apng: ByteArray get() = decode(
        "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAACXBIWXMAAAAAAAAAAQCEeRdzAAAACGFjVEwAAAADAAAAAM7tusAA",
        "AAAaZmNUTAAAAAAAAABAAAAAQAAAAAAAAAAAAAEABAAAuQvRPwAAAVNJREFUeJztmlEOhCAMBddkz+ahvZwbPpqQNa4gLdONb76J",
        "vI7VoLC8Hs6bDkAjAXQAGgmgA9BIAB2AJlzAvq771Zhl25boHGeECGgp+mz8bBmuAnoL/3WNWSJcBHgUfnbNaBHDAiKK/75+pIQh",
        "AdHF1/NESbgtYFbx9XwREm4JmF18Pa+3hG4BVPH1/J4SugTQxRueErQUbh2Y5e4bXl2gDmgZlO3uGx5doA6gA9BcCsja/sboY6AO",
        "oAPQSAAdgEYC6AA0EkAHoLkUUBYZmRdD+hYYRAJaBmV9DPRDxIFmAdm6QD9FnegSkKULsH0Bm5yUgO8MWQhCQpq9wcJsCel2hwuz",
        "JKQ9H1CIlpD+hEjBQnqK+KszQoaHiL8+JWbURTzynGANWVwLWgrTAWgkgA5AcxCQ4WsvmvrFrA6gA9BIAB2ARgLoADQHAdnX7t6o",
        "A+gANB/eFpvq8LPo0wAAABpmY1RMAAAAAQAAADkAAAA1AAAABAAAAAgAAQAEAAAyGF7CAAABHmZkQVQAAAACeJztmlsKAyEMRSvM",
        "YrqE7v+rS+hupvQjEEodjeZFes+3YzxcYXy1mzPn836O2rTHq2nWPDQ76zEj1muvIWwqKZW76mNH1kRSQ67X54qsuqSF4Hf/UlFV",
        "SWtBXkciqibpJcjrzYqqSHoL8rozotuSUYK8/kh0SzJakBiJuiwGolmWzJIicZUmkuyRLUWilyaSrIJYMutUJX5NWSRZBUhWAZJV",
        "gGQVxJKfH23mBQHWrpVZksw6ZbFpXiFbmjjI2vk4S5qm565UIFLU5QSdCkWIut6FUEFP0ZBbLSrsIRp6P0kDsBRNcdPMB6Ipm+7N",
        "AKEhm/71B8EHWvYdD0dbYAYs66oAySocGXYR1vxHktED8ACSVYBkFd7JopjSDzzgVwAAABpmY1RMAAAAAwAAADwAAAA1AAAABAAA",
        "AAgAAQAEAADhBACRAAABHmZkQVQAAAAEeJzt2ksOwjAMBFAqcSgWHIwjcDAW3AqUhSWratq0+DO4M+s2zpO7aD7TBSz39+Oz9czr",
        "9pyOjn89+qJlRpC95/fiU8F7oWtjjMJTwBbQ3phb8HCwB3Y+/ho6FOyN1XV66DBwFFbXW0KHgKOxuu4c7Q7Owur6Gu0KzsZKNBri",
        "xyMybmCU7kqky+ywRdC6K2nzYoerxxyM+jlL2OHqIbh6CK4egqvHHNxWJMg/H+xw9biAUT9rbgBYBq3L3MTzCEqXw/alpVgmOvzk",
        "QYpmoNPOlqR4JDr99FAmEYGGOR9u8UbD3QBokUlZwqHveEgs4H91i0eiJ32Ke1o6v2BGAgf2DsHVcz4wwmrGO6GrJbQQXD0EV8/p",
        "wF8LqJNNGnTIrAAAAABJRU5ErkJggg=="
    )

    /** PNG comum (o disco vermelho), 64x64. */
    val png: ByteArray get() = decode(
        "iVBORw0KGgoAAAANSUhEUgAAAEAAAABACAYAAACqaXHeAAAA5klEQVR42u2aQQ6DMAwEyT6OR/M5eu2lKqV2vCYzD4jXk5UQLdsG",
        "AAAAqzKyB5z7fn4NcRzjUQKuLO0iY7gsXiViuC0+W4Scl59xvpzDzZgj9+Wz56nD8plz1WX5rPnqtHxGDm2Lo263H52HBnS8/chc",
        "NAABTesflY8GIAABCEAAAhCAgI9U/mlxhX/z0QAETKiZa/1pwC8C3FoQlYcGVFh3uf1bDaiWED1fDiEq58opTMU8OYaaOUfO4aac",
        "H3nYst8IRYpo/ZXYXRnur9wAAI9lZD7LbZd+e+rwewACEIAABADAsrwAGuZoWUiggaIAAAAASUVORK5CYII="
    )

    /** JPEG (o disco vermelho sobre fundo preto), 64x64. */
    val jpeg: ByteArray get() = decode(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAA0JCgsKCA0LCgsODg0PEyAVExISEyccHhcgLikxMC4pLSwzOko+MzZGNywtQFdBRkxO",
        "UlNSMj5aYVpQYEpRUk//2wBDAQ4ODhMREyYVFSZPNS01T09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09PT09P",
        "T09PT09PT0//wAARCABAAEADASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUF",
        "BAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVW",
        "V1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi",
        "4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAEC",
        "AxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVm",
        "Z2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq",
        "8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDzCiip7K0mvrlYLdcsepPRR6n2obsOMXJ2W5BRXc2OgWFonzxieQjlpRkdug6D+fvWpXO8",
        "QuiPWp5TNq85W/E8yorvr3RrC9BMkIRySfMj+Vsk859fxrjdT06bTbnypeVPKOBww/x9q0hVUzlxOBqUNXqu5TooorQ4grufDtkt",
        "ppcb8GScCRiPQ9B+X6k1w1em1z4h6JHr5TTTnKb6fqFFFFcp7wVU1SyW/sJLc43EZQnsw6f59Cat0U07O5M4qcXF7M8yoq1qf/IU",
        "u/8Aru//AKEaq16Cd0fHSXLJoK77Q7wXulxOSS6Dy3ySTuHfPvwfxrgauaZqM2m3PmxcqeHQnhh/j71FWHOjrwOJ9hUu9nueg0VU",
        "sdStL9M28oLYyUPDDp2/Hr0q3XE01ufTRnGavF3QVBfXSWVnLcuMiNc49T2H4nFF3eW1lHvupljB6Z6n6DqetcXrOrSanNgZS3Q/",
        "In9T7/y/ndOm5PyOTGYyNCLSfvGczM7l3YszHJJOSTSUUV3Hy5astPu7/f8AZYvM2Y3fMBjPTqfarP8Awj+q/wDPr/5EX/GpdA1W",
        "DTPtHnpI3mbcbAD0z6ketbH/AAlVh/zxuf8Avlf8axlKonoj0aFDCypp1J2f9eRhf8I/qv8Az6/+RF/xq19g8Sf37n/wJH/xVaf/",
        "AAlVh/zxuf8Avlf8aP8AhKrD/njc/wDfK/41PNUfQ6FQwcdqj+//AIBiNoOru5d7YszHJJlUkn86b/wj+q/8+v8A5EX/ABrd/wCE",
        "qsP+eNz/AN8r/jR/wlVh/wA8bn/vlf8AGjnq9ifq2B/5+P7/APgGF/wj+q/8+v8A5EX/ABqC80u9sohLdQ7ELbQdynn8D7V0n/CV",
        "WH/PG5/75X/Gs3XdattSskhgSZWWQMS4AGMEdj71UZVG9UZ1qGEjBuE7v+vI/9k="
    )
}
