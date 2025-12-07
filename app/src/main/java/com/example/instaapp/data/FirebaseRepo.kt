package com.example.instaapp.data

import com.example.instaapp.UserComment
import com.google.firebase.firestore.Query
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.auth.ktx.auth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.storage.ktx.storage
import android.net.Uri
import com.google.firebase.firestore.FieldValue
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.StorageReference
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.FirebaseStorage
import android.os.Handler
import android.os.Looper
import android.util.Log

object FirebaseRepo {
    private fun mapAuthError(e: Exception?): String {
        val msg = e?.message ?: return "Terjadi kesalahan"
        val m = msg.lowercase()
        return when {
            m.contains("configuration_not_found") -> "Provider Auth belum diaktifkan di Firebase (Email/Password)."
            m.contains("invalid_login_credentials") -> "Login gagal: email atau password salah"
            m.contains("error_email_already_in_use") || m.contains("email_exists") -> "Email sudah terdaftar"
            m.contains("error_invalid_email") || m.contains("invalid_email") -> "Email tidak valid"
            m.contains("error_weak_password") || m.contains("weak_password") -> "Password terlalu lemah"
            else -> e.localizedMessage ?: msg
        }
    }
    // Validasi email dan password
    fun validateEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
    }
    fun validatePassword(password: String): Boolean {
        return password.length >= 6
    }

    // Register user
    fun registerUser(email: String, password: String, username: String, onResult: (Boolean, String?) -> Unit) {
        val em = email.trim()
        val un = username.trim()
        if (em.isBlank() || password.isBlank() || un.isBlank()) {
            onResult(false, "Semua kolom harus diisi")
            return
        }
        if (!validateEmail(em)) {
            onResult(false, "Email tidak valid")
            return
        }
        if (!validatePassword(password)) {
            onResult(false, "Password minimal 6 karakter")
            return
        }
        Firebase.auth.createUserWithEmailAndPassword(em, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val user = Firebase.auth.currentUser
                    if (user != null) {
                        val userData = mapOf(
                            "uid" to user.uid,
                            "email" to em,
                            "username" to un,
                            "createdAt" to System.currentTimeMillis()
                        )
                        Firebase.firestore.collection("users")
                            .document(user.uid)
                            .set(userData)
                            .addOnSuccessListener { onResult(true, null) }
                            .addOnFailureListener { e ->
                                val msg = e.message?.lowercase() ?: ""
                                if (msg.contains("permission_denied")) {
                                    onResult(true, null)
                                } else {
                                    onResult(false, mapAuthError(e))
                                }
                            }
                    } else {
                        onResult(false, "User tidak ditemukan")
                    }
                } else {
                    onResult(false, mapAuthError(task.exception))
                }
            }
    }

    // Login user
    fun loginUser(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        val em = email.trim()
        if (!validateEmail(em)) {
            onResult(false, "Email tidak valid")
            return
        }
        if (!validatePassword(password)) {
            onResult(false, "Password minimal 6 karakter")
            return
        }
        Firebase.auth.signInWithEmailAndPassword(em, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    onResult(true, null)
                } else {
                    onResult(false, mapAuthError(task.exception))
                }
            }
    }

    fun fetchComments(onResult: (List<UserComment>?, Exception?) -> Unit) {
        Firebase.firestore.collection("comments")
            .orderBy("time", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.mapNotNull { d ->
                    val id = d.getLong("id") ?: return@mapNotNull null
                    val user = d.getString("user") ?: return@mapNotNull null
                    val time = d.getLong("time") ?: System.currentTimeMillis()
                    val text = d.getString("text") ?: ""
                    val likes = (d.getLong("likes") ?: 0L).toInt()
                    val liked = d.getBoolean("liked") ?: false
                    UserComment(id, user, time, text, likes, liked)
                }
                onResult(list, null)
            }
            .addOnFailureListener { e -> onResult(null, e) }
    }

    fun postComment(c: UserComment, onResult: (Boolean) -> Unit) {
        val data = mapOf(
            "id" to c.id,
            "user" to c.user,
            "time" to c.time,
            "text" to c.text,
            "likes" to c.likes,
            "liked" to c.liked
        )
        Firebase.firestore.collection("comments")
            .document(c.id.toString())
            .set(data)
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    fun updateLike(id: Long, liked: Boolean, likes: Int, onResult: (Boolean) -> Unit) {
        Firebase.firestore.collection("comments")
            .document(id.toString())
            .update(mapOf("liked" to liked, "likes" to likes))
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    fun createPost(kind: String, localUri: Uri, caption: String, onResult: (Boolean, String?) -> Unit) {
        try {
            val user = Firebase.auth.currentUser ?: throw IllegalStateException("User belum login")
            val id = System.currentTimeMillis()
            val ext = if (kind == "video") "mp4" else "jpg"
            val ref = com.google.firebase.ktx.Firebase.storage.reference.child("posts/${user.uid}/$id.$ext")
            val meta = StorageMetadata.Builder()
                .setContentType(if (kind == "video") "video/mp4" else "image/jpeg")
                .build()
            ref.putFile(localUri, meta)
                .addOnSuccessListener {
                    retryDownloadUrl(ref, tries = 4, initialDelayMs = 300,
                        onSuccess = { urlStr ->
                            val data = mapOf(
                                "id" to id,
                                "uid" to user.uid,
                                "kind" to kind,
                                // Simpan HTTPS URL langsung + path
                                "uri" to urlStr,
                                "path" to ref.path.removePrefix("/"),
                                "caption" to caption,
                                "time" to System.currentTimeMillis(),
                                "likes" to 0
                            )
                            Firebase.firestore.collection("posts").document(id.toString()).set(data)
                                .addOnSuccessListener { onResult(true, null) }
                                .addOnFailureListener { e -> onResult(false, e.message) }
                        },
                        onFailure = { errMsg ->
                            onResult(false, errMsg)
                        }
                    )
                }
                .addOnFailureListener { e -> onResult(false, e.message) }
        } catch (e: Exception) {
            onResult(false, e.message)
        }
    }

    fun createPostFromUrl(mediaUrl: String, caption: String, onResult: (Boolean, String?) -> Unit) {
        try {
            val user = Firebase.auth.currentUser ?: throw IllegalStateException("User belum login")
            val id = System.currentTimeMillis()
            val low = mediaUrl.lowercase()
            val kind = if (low.endsWith(".mp4") || low.contains("/video")) "video" else "image"
            if (!low.startsWith("http")) throw IllegalArgumentException("URI harus HTTPS")
            kotlin.concurrent.thread {
                try {
                    val conn = java.net.URL(mediaUrl).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    val code = conn.responseCode
                    if (code in 200..399) {
                        val data = mapOf(
                            "id" to id,
                            "uid" to user.uid,
                            "kind" to kind,
                            "uri" to mediaUrl,
                            "caption" to caption,
                            "time" to System.currentTimeMillis(),
                            "likes" to 0
                        )
                        Firebase.firestore.collection("posts").document(id.toString()).set(data)
                            .addOnSuccessListener { onResult(true, null) }
                            .addOnFailureListener { e -> onResult(false, e.message) }
                    } else {
                        onResult(false, "URL tidak ditemukan (HTTP $code)")
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    onResult(false, e.message)
                }
            }
        } catch (e: Exception) {
            onResult(false, e.message)
        }
    }

    fun updatePostLike(postId: Long, liked: Boolean, onResult: (Boolean) -> Unit) {
        val inc = if (liked) 1 else -1
        Firebase.firestore.collection("posts")
            .document(postId.toString())
            .update(mapOf("likes" to FieldValue.increment(inc.toLong())))
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    fun fetchRecentPosts(limit: Long, onResult: (List<PostItem>?, Exception?) -> Unit) {
        Firebase.firestore.collection("posts")
            .orderBy("time", Query.Direction.DESCENDING)
            .limit(limit)
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.mapNotNull { d ->
                    val id = d.getLong("id") ?: return@mapNotNull null
                    val uid = d.getString("uid") ?: return@mapNotNull null
                    val kind = d.getString("kind") ?: return@mapNotNull null
                    val uri = d.getString("uri") ?: ""
                    val caption = d.getString("caption") ?: ""
                    val time = d.getLong("time") ?: System.currentTimeMillis()
                    val likes = (d.getLong("likes") ?: 0L).toInt()
                    PostItem(id, kind, uri, caption, time, uid, likes)
                }
                onResult(list, null)
            }
            .addOnFailureListener { e -> onResult(null, e) }
    }

    fun storageHealthCheck(onResult: (Boolean, String?) -> Unit) {
        try {
            val user = Firebase.auth.currentUser ?: throw IllegalStateException("User belum login")
            val bucket = FirebaseApp.getInstance().options.storageBucket ?: throw IllegalStateException("Storage bucket belum dikonfigurasi")
            val storage = FirebaseStorage.getInstance("gs://$bucket")
            val ref = storage.reference.child("posts/${user.uid}/__diag.txt")
            val bytes = "ping".toByteArray()
            ref.putBytes(bytes)
                .addOnSuccessListener {
                    ref.downloadUrl
                        .addOnSuccessListener { onResult(true, null) }
                        .addOnFailureListener { e -> onResult(false, e.message) }
                }
                .addOnFailureListener { e -> onResult(false, e.message) }
        } catch (e: Exception) {
            onResult(false, e.message)
        }
    }

    fun fetchCommentsForPost(postId: Long, onResult: (List<UserComment>?, Exception?) -> Unit) {
        Firebase.firestore.collection("posts").document(postId.toString()).collection("comments")
            .orderBy("time", Query.Direction.DESCENDING)
            .limit(50)
            .get()
            .addOnSuccessListener { snap ->
                val list = snap.documents.mapNotNull { d ->
                    val id = d.getLong("id") ?: return@mapNotNull null
                    val user = d.getString("user") ?: return@mapNotNull null
                    val time = d.getLong("time") ?: System.currentTimeMillis()
                    val text = d.getString("text") ?: ""
                    val likes = (d.getLong("likes") ?: 0L).toInt()
                    val liked = d.getBoolean("liked") ?: false
                    com.example.instaapp.UserComment(id, user, time, text, likes, liked)
                }
                onResult(list, null)
            }
            .addOnFailureListener { e -> onResult(null, e) }
    }

    fun postCommentForPost(postId: Long, c: UserComment, onResult: (Boolean) -> Unit) {
        val data = mapOf(
            "id" to c.id,
            "user" to c.user,
            "time" to c.time,
            "text" to c.text,
            "likes" to c.likes,
            "liked" to c.liked
        )
        Firebase.firestore.collection("posts").document(postId.toString()).collection("comments")
            .document(c.id.toString())
            .set(data)
            .addOnSuccessListener { onResult(true) }
            .addOnFailureListener { onResult(false) }
    }

    private fun buildPublicUrl(ref: StorageReference): String {
        val bucket = FirebaseApp.getInstance().options.storageBucket ?: ""
        val path = ref.path.removePrefix("/")
        return "https://firebasestorage.googleapis.com/v0/b/$bucket/o/${Uri.encode(path)}?alt=media"
    }

    private fun retryDownloadUrl(ref: StorageReference, tries: Int, initialDelayMs: Long, onSuccess: (String) -> Unit, onFailure: (String) -> Unit) {
        ref.downloadUrl
            .addOnSuccessListener { url -> onSuccess(url.toString()) }
            .addOnFailureListener { e ->
                if (tries <= 1) {
                    onFailure(e.message ?: "downloadUrl 404")
                } else {
                    Handler(Looper.getMainLooper()).postDelayed({
                        retryDownloadUrl(ref, tries - 1, initialDelayMs * 2, onSuccess, onFailure)
                    }, initialDelayMs)
                }
            }
    }
}
