package cl.avelyn.bd_control_app.model.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPolicyTest {

    private val policy = AccessPolicy(
        listOf(
            DevicePermission("sensor-1", canView = true, canControl = false),
            DevicePermission("rele-1", canView = true, canControl = true),
            DevicePermission("rele-solo-control", canView = false, canControl = true)
        )
    )

    @Test
    fun `denegar por defecto un dispositivo sin fila`() {
        assertFalse(policy.canView("rele-desconocido"))
        assertFalse(policy.canControl("rele-desconocido"))
    }

    @Test
    fun `solo lectura permite ver pero no controlar`() {
        assertTrue(policy.canView("sensor-1"))
        assertFalse(policy.canControl("sensor-1"))
    }

    @Test
    fun `permiso completo permite ver y controlar`() {
        assertTrue(policy.canView("rele-1"))
        assertTrue(policy.canControl("rele-1"))
    }

    @Test
    fun `controlar sin poder ver no vale`() {
        assertFalse(policy.canView("rele-solo-control"))
        assertFalse(policy.canControl("rele-solo-control"))
    }

    @Test
    fun `sin sesion se deniega todo`() {
        assertFalse(AccessPolicy.DENY_ALL.canView("rele-1"))
        assertFalse(SessionManager().policy.canView("rele-1"))
    }

    @Test
    fun `el comodin da acceso a todo y una fila especifica lo sobrescribe`() {
        val admin = AccessPolicy(
            listOf(
                DevicePermission("*", canView = true, canControl = true),
                DevicePermission("rele-bloqueado", canView = true, canControl = false),
                DevicePermission("sensor-oculto", canView = false, canControl = false)
            )
        )
        assertTrue(admin.canView("cualquiera"))
        assertTrue(admin.canControl("cualquiera"))
        assertTrue(admin.canView("rele-bloqueado"))
        assertFalse(admin.canControl("rele-bloqueado"))
        assertFalse(admin.canView("sensor-oculto"))
    }

    @Test
    fun `la sesion abre y cierra`() {
        val session = SessionManager()
        session.open("ana", policy)
        assertTrue(session.policy.canControl("rele-1"))
        assertTrue(session.username == "ana")
        session.close()
        assertFalse(session.policy.canView("rele-1"))
        assertTrue(session.username == null)
    }
}
