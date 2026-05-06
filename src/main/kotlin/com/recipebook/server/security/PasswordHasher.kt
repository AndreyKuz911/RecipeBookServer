package com.recipebook.server.security

import org.mindrot.jbcrypt.BCrypt

class PasswordHasher {
    fun hash(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt())

    fun verify(password: String, hash: String): Boolean = BCrypt.checkpw(password, hash)
}
