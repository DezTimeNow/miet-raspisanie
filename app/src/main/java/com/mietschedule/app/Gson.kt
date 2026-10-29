package com.mietschedule.app

import com.google.gson.Gson

/** Общий экземпляр Gson: его создание недешёвое, а моделей много. */
object GsonHolder {
    val gson: Gson = Gson()
}
