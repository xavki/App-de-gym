package com.gymflow

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DataManager(context: Context) {
    private val sharedPreferences = context.getSharedPreferences("GymFlowPrefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveRoutines(routines: List<WorkoutSession>) {
        val json = gson.toJson(routines)
        sharedPreferences.edit().putString("routines", json).apply()
    }

    fun loadRoutines(): List<WorkoutSession> {
        val json = sharedPreferences.getString("routines", null) ?: return emptyList()
        val type = object : TypeToken<List<WorkoutSession>>() {}.type
        return gson.fromJson(json, type)
    }
}
