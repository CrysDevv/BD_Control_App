package cl.avelyn.bd_control_app.model.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {

    @Test
    fun `deberia verificar correctamente la contrasena correcta`() {
        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash("Bodega#2026".toCharArray(), salt)

        assertTrue(PasswordHasher.verify("Bodega#2026".toCharArray(), salt, hash))
    }

    @Test
    fun `deberia rechazar la contrasena cuando es incorrecta`() {
        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash("Bodega#2026".toCharArray(), salt)

        assertFalse(PasswordHasher.verify("otra-clave".toCharArray(), salt, hash))
    }

    @Test
    fun `deberia generar hashes distintos para la misma contrasena con salts distintas`() {
        val salt1 = PasswordHasher.newSalt()
        val salt2 = PasswordHasher.newSalt()

        val hash1 = PasswordHasher.hash("Bodega#2026".toCharArray(), salt1)
        val hash2 = PasswordHasher.hash("Bodega#2026".toCharArray(), salt2)

        assertFalse(hash1.contentEquals(hash2))
    }

    @Test
    fun `es compatible con el hash que genera tools new_user py`() {
        // Valor calculado con hashlib.pbkdf2_hmac("sha256", ..., 120000, 32) de Python, que es lo que
        // usa tools/new_user.py para dar de alta usuarios. Si este test falla, las cuentas creadas
        // con la herramienta dejarían de poder iniciar sesión.
        val salt = ByteArray(16) { it.toByte() }
        val expected = java.util.Base64.getDecoder().decode("ymUqt7kFEc9k4YLr1scFbqMwY2pbVfV2Zk2WogEFV0I=")
        assertTrue(PasswordHasher.verify("Bodega#2026".toCharArray(), salt, expected))
    }
}
