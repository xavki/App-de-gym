package com.gymflow

// ════════════════════════════════════════════════════════════════════════════
// Figuras anatómicas (vista frontal y posterior) en trazados vectoriales propios.
// Dibujo original para la app: no procede de ningún recurso externo.
// Coordenadas en una caja de 200 × 440 por figura; solo se define la mitad
// izquierda (x ≤ 100) y la derecha se dibuja en espejo respecto a x = 100.
// group = id de MuscleActivity.GROUPS, o null para zonas sin seguimiento
// (silueta, articulaciones, aductores, tibial).
// ════════════════════════════════════════════════════════════════════════════

internal class MusclePart(val group: String?, val d: String)

internal object MuscleAtlas {
    const val WIDTH = 200f
    const val HEIGHT = 440f

    /** Silueta común a las dos vistas (cabeza, cuello, manos, rodillas y pies quedan en gris base). */
    const val SILHOUETTE =
        "M100,6 C89,6 81,15 81,29 C81,41 85,50 90,55 L90,63 C82,69 71,73 62,76 " +
        "C50,79 43,88 41,101 C39,116 39,134 40,150 C40,158 39,164 38,170 " +
        "C35,188 32,206 31,224 C29,234 29,248 32,257 C35,264 43,263 44,255 " +
        "C45,246 44,236 44,229 C48,212 54,192 57,174 C59,162 61,140 63,118 L67,116 " +
        "C68,140 71,166 72,186 C70,200 65,214 63,230 C58,256 59,288 69,316 " +
        "C67,330 66,352 69,372 C71,388 73,400 73,410 C68,418 68,428 75,431 L92,431 " +
        "C94,422 92,414 90,408 C92,388 94,356 92,334 C91,326 92,320 93,314 " +
        "C96,292 98,268 99,244 L100,242 Z"

    val front = listOf(
        MusclePart("back", "M90,64 C84,69 74,73 66,76 C74,77 82,76 90,72 Z"),                       // trapecio superior
        MusclePart("shoulders", "M66,78 C54,79 45,87 43,100 C42,110 43,120 46,128 C50,118 56,106 62,98 C66,92 68,85 66,78 Z"),
        MusclePart("chest", "M99,80 L99,122 C90,128 78,128 69,121 C66,112 65,100 67,92 C70,84 80,80 99,80 Z"),
        MusclePart("biceps", "M47,130 C51,120 56,110 61,104 C63,120 62,140 59,156 C56,164 50,165 46,160 C43,150 44,140 47,130 Z"),
        MusclePart("forearms", "M40,172 C37,190 34,206 33,222 L43,226 C47,210 52,192 56,174 C51,178 45,178 40,172 Z"),
        MusclePart("abs", "M70,126 C76,132 82,136 86,138 L86,198 C82,204 77,208 72,210 C73,196 73,170 71,150 C70,140 70,132 70,126 Z"), // oblicuo
        MusclePart("abs", "M88,130 L99,130 L99,147 L88,147 Z"),
        MusclePart("abs", "M88,150 L99,150 L99,168 L88,168 Z"),
        MusclePart("abs", "M88,171 L99,171 L99,189 L88,189 Z"),
        MusclePart("abs", "M88,192 L99,192 L99,228 C95,226 91,222 88,216 Z"),
        MusclePart("quads", "M63,236 C57,258 57,286 66,308 C70,315 77,318 83,314 C87,300 88,282 87,262 C87,252 86,246 85,242 C78,238 70,236 63,236 Z"), // vasto lateral y recto femoral
        MusclePart("quads", "M89,276 C93,284 96,296 95,306 C94,313 90,317 86,316 C89,306 90,292 89,276 Z"),                          // vasto medial
        MusclePart(null, "M87,242 C89,250 90,262 90,272 L93,274 C96,264 98,254 98,246 C94,243 91,242 87,242 Z"),                    // aductores
        MusclePart("calves", "M69,330 C65,344 66,362 70,378 C72,372 74,356 74,338 C73,334 71,331 69,330 Z"),                         // sóleo / peroneos
        MusclePart("calves", "M84,332 C90,338 94,350 93,364 C92,376 89,384 86,390 C86,372 85,350 84,332 Z"),                         // gemelo interno
    )

    val back = listOf(
        MusclePart("back", "M99,58 L99,160 C96,146 93,132 89,120 C84,106 76,94 64,80 C72,76 82,72 90,64 C94,62 97,60 99,58 Z"), // trapecio
        MusclePart("shoulders", "M64,80 C53,80 45,88 43,100 C42,110 43,120 46,128 C50,118 56,106 62,98 C66,93 68,86 64,80 Z"),
        MusclePart("back", "M66,96 C74,102 82,110 88,122 C82,130 74,132 68,130 C66,118 65,106 66,96 Z"),   // infraespinoso / redondo
        MusclePart("back", "M68,134 C76,136 84,132 90,126 C91,142 91,160 90,176 C86,186 80,194 75,200 C74,180 71,156 68,134 Z"), // dorsal ancho
        MusclePart("back", "M99,164 L99,226 C95,228 91,227 88,224 C88,208 89,190 91,176 C93,170 96,166 99,164 Z"),                 // erectores / lumbares
        MusclePart("abs", "M75,203 C80,198 84,192 86,186 L86,222 C80,222 75,224 70,227 C71,218 72,210 75,203 Z"),                  // oblicuo
        MusclePart("triceps", "M47,130 C51,120 56,110 61,104 C63,120 62,140 59,156 C56,164 50,165 46,160 C43,150 44,140 47,130 Z"),
        MusclePart("forearms", "M40,172 C37,190 34,206 33,222 L43,226 C47,210 52,192 56,174 C51,178 45,178 40,172 Z"),
        MusclePart("glutes", "M99,228 L99,272 C90,279 77,279 68,271 C62,260 62,244 65,232 C74,228 87,226 99,228 Z"),
        MusclePart("hamstrings", "M64,279 C62,292 65,304 71,314 C74,316 77,316 79,312 C79,300 79,288 80,281 C74,282 68,282 64,279 Z"), // bíceps femoral
        MusclePart("hamstrings", "M82,282 C82,292 82,304 82,312 C85,316 89,316 91,312 C94,302 96,290 97,278 C92,281 87,282 82,282 Z"), // semitendinoso
        MusclePart("calves", "M69,330 C64,344 65,360 71,372 C74,378 78,378 80,372 C80,358 80,342 80,330 C76,326 72,327 69,330 Z"),     // gemelo externo
        MusclePart("calves", "M82,330 C82,344 82,360 83,376 C86,384 90,382 92,372 C95,358 95,342 92,330 C88,326 85,326 82,330 Z"),     // gemelo interno
    )
}
