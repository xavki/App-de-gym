package com.gymflow.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToLong

private val MIN_DATE: LocalDate = LocalDate.of(2000, 1, 1)
private const val MAX_SERVINGS = 10.0
private const val MAX_HISTORY_DAYS = 366L

/**
 * /api/nutrition: objetivos, catálogo de comidas y registro diario.
 * Va dentro de un authenticate("firebase", "panel"): es lo único que un panel
 * vinculado puede escribir; el resto de la API sigue siendo de solo lectura para él.
 * El usuario siempre sale del token, nunca del cuerpo de la petición.
 */
fun Route.nutritionRoutes(repo: NutritionRepository, clock: Clock, zone: ZoneId) {
    fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), zone)

    route("/api/nutrition") {
        // Objetivos vigentes en ?date=YYYY-MM-DD (por defecto, hoy)
        get("/goals") {
            val user = call.nutritionUser()
            val date = call.dateParam("date", today(), today())
            call.respond(io { repo.goals(user.uid, date) })
        }

        put("/goals") {
            val user = call.nutritionUser()
            val goals = validGoals(call.receive<NutritionGoals>())
            call.respond(io { repo.saveGoals(user.uid, user.email, goals, today()) })
        }

        get("/meals") {
            call.respond(MealsResponse(io { repo.meals() }))
        }

        get("/logs") {
            val user = call.nutritionUser()
            val date = call.dateParam("date", today(), today())
            call.respond(LogsResponse(date.toString(), io { repo.logs(user.uid, date) }))
        }

        post("/logs") {
            val user = call.nutritionUser()
            val req = call.receive<CreateLogRequest>()
            val id = parseUuid(req.id)
            val date = req.date?.let { parseDate(it, today()) } ?: today()
            val servings = validServings(req.servings)
            val source = when {
                req.mealCode != null && req.custom == null -> LogSource.Catalog(req.mealCode)
                req.mealCode == null && req.custom != null -> validCustom(req.custom)
                else -> throw BadRequestException("Indica una comida del plan (mealCode) o una personalizada (custom), no las dos")
            }
            when (val r = io { repo.create(user.uid, user.email, id, source, date, servings) }) {
                is CreateLogResult.Created -> call.respond(HttpStatusCode.Created, r.log)
                is CreateLogResult.AlreadyExists -> call.respond(HttpStatusCode.OK, r.log)
                CreateLogResult.UnknownMeal -> call.respond(HttpStatusCode.BadRequest, ErrorResponse("Comida desconocida: ${req.mealCode?.take(20)}"))
                CreateLogResult.IdConflict -> call.respond(HttpStatusCode.Conflict, ErrorResponse("Ese id ya se ha usado para otro registro"))
            }
        }

        patch("/logs/{id}") {
            val user = call.nutritionUser()
            val id = parseUuid(call.parameters["id"].orEmpty())
            val servings = validServings(call.receive<UpdateLogRequest>().servings)
            val log = io { repo.updateServings(user.uid, id, servings) }
                ?: return@patch call.respond(HttpStatusCode.NotFound, ErrorResponse("Registro no encontrado"))
            call.respond(log)
        }

        delete("/logs/{id}") {
            val user = call.nutritionUser()
            val id = parseUuid(call.parameters["id"].orEmpty())
            if (io { repo.delete(user.uid, id) }) call.respond(HttpStatusCode.NoContent)
            else call.respond(HttpStatusCode.NotFound, ErrorResponse("Registro no encontrado"))
        }

        get("/summary") {
            val user = call.nutritionUser()
            val date = call.dateParam("date", today(), today())
            call.respond(io { repo.summary(user.uid, date) })
        }

        // Totales por día para el historial: ?from=YYYY-MM-DD&to=YYYY-MM-DD (por defecto, los últimos 30 días)
        get("/days") {
            val user = call.nutritionUser()
            val today = today()
            val to = call.dateParam("to", today, today)
            val from = call.dateParam("from", to.minusDays(29), today)
            if (from.isAfter(to)) throw BadRequestException("'from' no puede ser posterior a 'to'")
            if (ChronoUnit.DAYS.between(from, to) >= MAX_HISTORY_DAYS) throw BadRequestException("Máximo $MAX_HISTORY_DAYS días por consulta")
            call.respond(DaysResponse(from.toString(), to.toString(), io { repo.days(user.uid, from, to) }))
        }
    }
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private fun ApplicationCall.nutritionUser(): UserPrincipal = principal<UserPrincipal>() ?: error("Ruta sin autenticación")

/** Parámetro de fecha opcional: [default] si no viene; validado con [today]. */
private fun ApplicationCall.dateParam(name: String, default: LocalDate, today: LocalDate): LocalDate =
    request.queryParameters[name]?.let { parseDate(it, today) } ?: default

/** Día natural válido: formato YYYY-MM-DD, desde el 2000 y como mucho mañana (margen de zona horaria). */
internal fun parseDate(value: String, today: LocalDate): LocalDate {
    val date = try {
        LocalDate.parse(value)
    } catch (_: DateTimeParseException) {
        throw BadRequestException("Fecha no válida (usa YYYY-MM-DD)")
    }
    if (date.isBefore(MIN_DATE) || date.isAfter(today.plusDays(1))) throw BadRequestException("Fecha fuera de rango: $date")
    return date
}

private fun parseUuid(value: String): UUID = try {
    UUID.fromString(value).also { if (it.toString() != value.lowercase()) throw IllegalArgumentException() }
} catch (_: IllegalArgumentException) {
    throw BadRequestException("Id no válido (se espera un UUID)")
}

/** Raciones entre 0,05 y 10, redondeadas a 2 decimales. */
internal fun validServings(value: Double): Double {
    if (!value.isFinite() || value <= 0 || value > MAX_SERVINGS) throw BadRequestException("Las raciones deben estar entre 0 y $MAX_SERVINGS")
    val rounded = (value * 100).roundToLong() / 100.0
    if (rounded <= 0) throw BadRequestException("Las raciones deben ser mayores que 0")
    return rounded
}

/** Comida personalizada: nombre de 1 a 100 caracteres y valores por ración con 1 decimal. */
internal fun validCustom(food: CustomFood): LogSource.Custom {
    val name = food.name.trim()
    if (name.isEmpty() || name.length > 100) throw BadRequestException("El nombre debe tener entre 1 y 100 caracteres")
    if (name.any { it.isISOControl() }) throw BadRequestException("El nombre contiene caracteres no válidos")
    fun check(v: Double, max: Int, what: String): Double {
        if (!v.isFinite() || v < 0 || v > max) throw BadRequestException("$what debe estar entre 0 y $max")
        return (v * 10).roundToLong() / 10.0
    }
    return LogSource.Custom(
        name,
        Macros(
            kcal = check(food.kcal, 5000, "Las calorías por ración"),
            proteinG = check(food.proteinG, 500, "La proteína por ración (g)"),
            fatG = check(food.fatG, 500, "La grasa por ración (g)"),
            carbsG = check(food.carbsG, 500, "Los carbohidratos por ración (g)"),
        ),
    )
}

internal fun validGoals(g: NutritionGoals): NutritionGoals {
    fun check(v: Double, max: Int, what: String): Double {
        if (!v.isFinite() || v < 0 || v > max) throw BadRequestException("$what debe estar entre 0 y $max g")
        return (v * 10).roundToLong() / 10.0
    }
    if (g.kcal !in 500..10000) throw BadRequestException("Las calorías deben estar entre 500 y 10000")
    return NutritionGoals(
        kcal = g.kcal,
        proteinG = check(g.proteinG, 1000, "La proteína"),
        fatG = check(g.fatG, 1000, "La grasa"),
        carbsG = check(g.carbsG, 1500, "Los carbohidratos"),
    )
}
