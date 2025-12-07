package com.example.instaapp.data

data class PostItem(
    val id: Long,
    val kind: String,
    val uri: String,
    val caption: String,
    val time: Long,
    val uid: String,
    val likes: Int
)
