package com.travelsketch.data.model

import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.PropertyName

@IgnoreExtraProperties
data class Box(
    @get:PropertyName("box_x") @set:PropertyName("box_x") var boxX: Int = 0,
    @get:PropertyName("box_y") @set:PropertyName("box_y") var boxY: Int = 0,
    @get:PropertyName("box_z") @set:PropertyName("box_z") var boxZ: Int = 0,
    @get:PropertyName("data") @set:PropertyName("data") var data: String = "",
    @get:PropertyName("degree") @set:PropertyName("degree") var degree: Int = 0,
    @get:PropertyName("height") @set:PropertyName("height") var height: Int = 0,
    @get:PropertyName("latitude") @set:PropertyName("latitude") var latitude: Double? = null,
    @get:PropertyName("longitude") @set:PropertyName("longitude") var longitude: Double? = null,
    @get:PropertyName("time") @set:PropertyName("time") var time: Int? = null,
    @get:PropertyName("width") @set:PropertyName("width") var width: Int = 0,
    @get:PropertyName("type") @set:PropertyName("type") var type: String = "TEXT"
) {
    override fun toString(): String {
        return "Box(boxX=$boxX, boxY=$boxY, boxZ=$boxZ, data='$data', degree=$degree, height=$height, latitude=$latitude, longitude=$longitude, time=$time, width=$width, type='$type')"
    }
}