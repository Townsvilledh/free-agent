package org.freeagent.app

/**
 * ACCOUNT SWITCHING + LOG-OUT AS SCRIPTED LANE
 * MACHINERY (v1.0.10, Fix 3).
 *
 * Born of 2026-11-27 ~16:30: she had NO log-out or
 * account-switching logic at all. His Connect sign-in
 * sat in her browser, unverifiable and un-leavable —
 * the 1.0.8 registry recorded accounts and stated a
 * switch precondition, but nothing could EXECUTE a
 * switch, and her creation lane's signup needed a
 * signed-out browser it had no way to reach.
 *
 * The routines here are SCRIPTED, not model-driven:
 * fixed steps over the page's own controls (account
 * menu → Log out → confirm; login flow → username →
 * password → Log in), each step's effect verified
 * from the page, and the whole routine's outcome
 * proven by the navigation identity read
 * ([XIdentityRead]) — never by the taps landing. A
 * switch is sign-out then sign-in, in that order,
 * each half verified; the registry's active entry
 * moves only when the caller's read wrapper applies
 * a VERIFIED conclusion (the runner's wrapper stamps
 * the record + registry + sense on conclusions only —
 * an unreadable read moves nothing).
 *
 * Sign-in discipline (the standing security design):
 * HER account signs in autonomously — the caller
 * reads her password from her vault and hands it to
 * the routine as a value typed into the page's
 * SECRET field; it is never logged, never returned,
 * never placed in any message. HIS accounts are
 * never attempted: a switch to his account signs out
 * and then PARKS for his own login in the panel
 * ([SwitchResult.NeedsUserLogin] — the caller stamps
 * the hand-back park).
 *
 * Pure composition over a [Driver]; pinned by
 * Lille110Test against fake drivers.
 */

/** A concluding identity read, supplied by the
 *  caller: the argument is mayNavigate (the
 *  navigation proof, [XIdentityRead.conclude]).
 *  The caller's wrapper applies every conclusion
 *  to the record, the registry and the sense —
 *  conclusions are the only thing that moves
 *  them. */
typealias IdentityRead = (Boolean) -> XIdentityRead.Outcome

object AccountSession {

    /** The driver's surface: the identity read's
     *  surface plus the three page actions the
     *  routines perform. Every action returns the
     *  page state it landed on. */
    interface Driver : XIdentityRead.Driver {
        fun clickRef(ref: Int): BrowserPerception.PageState
        fun clickText(text: String): BrowserPerception.PageState
        fun type(
            ref: Int, value: String, secret: Boolean
        ): BrowserPerception.PageState
    }

    // ---------- step names (a failure names its step) ----------

    const val STEP_READ = "read the current sign-in"
    const val STEP_MENU = "open the account menu"
    const val STEP_CHOOSE_LOGOUT = "choose Log out"
    const val STEP_CONFIRM_LOGOUT = "confirm the log-out"
    const val STEP_VERIFY_LOGOUT = "verify the sign-out"
    const val STEP_USERNAME = "enter the username"
    const val STEP_NEXT = "continue past the username"
    const val STEP_PASSWORD = "enter the password"
    const val STEP_SUBMIT = "submit the login"
    const val STEP_VERIFY_LOGIN = "verify the sign-in"

    /** Control-flow carrier for a driver action that
     *  threw: the step it belonged to + the reason. */
    private class StepFailure(
        val step: String, val reason: String
    ) : Exception(reason)

    private fun <T> act(step: String, block: () -> T): T =
        try {
            block()
        } catch (e: StepFailure) {
            throw e
        } catch (e: Exception) {
            throw StepFailure(step, "the action didn't land")
        }

    // ---------- page element finders (pure) ----------

    private fun normText(el: BrowserPerception.Element): String =
        el.text.trim().lowercase()

    /** The account menu trigger: X's account-switcher
     *  button. (The profile tab is the reveal's other
     *  door — [XIdentityRead.Driver.revealAccountMenu]
     *  already knows both.) */
    fun accountMenuTrigger(
        state: BrowserPerception.PageState
    ): BrowserPerception.Element? = state.elements.firstOrNull {
        it.testid == "SideNav_AccountSwitcher_Button"
    }

    /** A Log out control: the menu's "Log out" /
     *  "Log out @handle" item, or a logout link. */
    fun logOutControl(
        state: BrowserPerception.PageState
    ): BrowserPerception.Element? = state.elements.firstOrNull {
        normText(it).startsWith("log out") ||
            it.href.contains("/logout")
    }

    /** The confirmation's own button: a control whose
     *  whole label is "Log out" (the menu item carries
     *  the handle; the confirm does not). */
    fun confirmLogOutControl(
        state: BrowserPerception.PageState
    ): BrowserPerception.Element? = state.elements.firstOrNull {
        normText(it) == "log out" &&
            (it.tag == "button" || it.role == "button" ||
                it.type == "submit")
    }

    /** The login flow's username field: a non-secret
     *  input — by autocomplete, by name, by label, in
     *  that order of confidence. */
    fun usernameField(
        state: BrowserPerception.PageState
    ): BrowserPerception.Element? {
        val inputs = state.elements.filter {
            it.tag == "input" && !it.secret &&
                it.type != "password"
        }
        return inputs.firstOrNull {
            it.name.contains("username", ignoreCase = true)
        } ?: inputs.firstOrNull { it.name == "text" }
            ?: inputs.firstOrNull {
                it.placeholder.contains("username", true) ||
                    it.placeholder.contains("email", true) ||
                    it.placeholder.contains("phone", true) ||
                    it.text.contains("username", true)
            }
            ?: inputs.firstOrNull {
                it.type == "text" || it.type.isEmpty()
            }
    }

    /** The login flow's password field: SECRET by the
     *  page's own flags. */
    fun passwordField(
        state: BrowserPerception.PageState
    ): BrowserPerception.Element? = state.elements.firstOrNull {
        it.secret || it.type == "password"
    }

    /** A button-shaped control with exactly this
     *  label ("Next", "Log in"). */
    fun buttonWithText(
        state: BrowserPerception.PageState, text: String
    ): BrowserPerception.Element? = state.elements.firstOrNull {
        normText(it) == text.lowercase() &&
            (it.tag == "button" || it.role == "button" ||
                it.type == "submit" || it.tag == "a")
    }

    // ---------- sign-out ----------

    sealed class SignOutResult {
        object AlreadySignedOut : SignOutResult()
        object SignedOut : SignOutResult()
        data class Failed(
            val step: String, val reason: String
        ) : SignOutResult()
    }

    /**
     * THE SIGN-OUT ROUTINE: account menu → Log out →
     * confirm, then verify-by-state (the navigation
     * read must conclude signed OUT — the taps landing
     * proves nothing). Already signed out is a
     * conclusion, not an error.
     */
    fun signOut(driver: Driver, read: IdentityRead): SignOutResult {
        try {
            when (val o = act(STEP_READ) { read(true) }) {
                is XIdentityRead.Outcome.SignedOut ->
                    return SignOutResult.AlreadySignedOut
                is XIdentityRead.Outcome.Unreadable ->
                    return SignOutResult.Failed(STEP_READ, o.reason)
                is XIdentityRead.Outcome.SignedIn -> Unit
            }
            var state = act(STEP_MENU) { driver.currentState() }
            if (logOutControl(state) == null) {
                val trigger = accountMenuTrigger(state)
                state = if (trigger != null) {
                    act(STEP_MENU) { driver.clickRef(trigger.ref) }
                } else {
                    act(STEP_MENU) { driver.revealAccountMenu() }
                }
                if (logOutControl(state) == null) {
                    state = act(STEP_MENU) {
                        driver.revealAccountMenu()
                    }
                }
            }
            val control = logOutControl(state)
                ?: return SignOutResult.Failed(
                    STEP_MENU,
                    "the account menu opened without a " +
                        "Log out control"
                )
            state = act(STEP_CHOOSE_LOGOUT) {
                driver.clickRef(control.ref)
            }
            confirmLogOutControl(state)?.let { confirm ->
                state = act(STEP_CONFIRM_LOGOUT) {
                    driver.clickRef(confirm.ref)
                }
            }
            return when (val o = act(STEP_VERIFY_LOGOUT) {
                read(true)
            }) {
                is XIdentityRead.Outcome.SignedOut ->
                    SignOutResult.SignedOut
                is XIdentityRead.Outcome.SignedIn ->
                    SignOutResult.Failed(
                        STEP_VERIFY_LOGOUT,
                        "the browser still reads signed in " +
                            "as @${o.handle}"
                    )
                is XIdentityRead.Outcome.Unreadable ->
                    SignOutResult.Failed(STEP_VERIFY_LOGOUT, o.reason)
            }
        } catch (e: StepFailure) {
            return SignOutResult.Failed(e.step, e.reason)
        }
    }

    // ---------- sign-in (hers, from her vault) ----------

    sealed class SignInResult {
        data class SignedIn(val handle: String) : SignInResult()
        data class Walled(
            val kind: String, val detail: String
        ) : SignInResult()
        data class Failed(
            val step: String, val reason: String
        ) : SignInResult()
    }

    /**
     * THE SIGN-IN ROUTINE (her account only — the
     * caller supplies her handle and her vault
     * password; his accounts never reach this code).
     * The login flow's own steps, each verified from
     * the page it lands on; a presented human check is
     * a WALL, returned for the caller to park on —
     * never solved, never bypassed. The outcome is
     * proven by the navigation read naming the target
     * handle.
     */
    fun signIn(
        driver: Driver, handle: String, password: String,
        read: IdentityRead
    ): SignInResult {
        try {
            var state = act(STEP_USERNAME) {
                driver.navigate(XIdentityRead.LOGIN_URL)
            }
            state.challenge?.let {
                return SignInResult.Walled(it.kind, it.detail)
            }
            val user = usernameField(state)
                ?: return SignInResult.Failed(
                    STEP_USERNAME,
                    "no username field on the login page"
                )
            state = act(STEP_USERNAME) {
                driver.type(user.ref, handle, false)
            }
            state.challenge?.let {
                return SignInResult.Walled(it.kind, it.detail)
            }
            val next = buttonWithText(state, "Next")
                ?: return SignInResult.Failed(
                    STEP_NEXT, "no Next button on the login page"
                )
            state = act(STEP_NEXT) { driver.clickRef(next.ref) }
            state.challenge?.let {
                return SignInResult.Walled(it.kind, it.detail)
            }
            val pass = passwordField(state)
                ?: return SignInResult.Failed(
                    STEP_PASSWORD,
                    "no password field appeared — X asked " +
                        "for something else first"
                )
            state = act(STEP_PASSWORD) {
                driver.type(pass.ref, password, true)
            }
            state.challenge?.let {
                return SignInResult.Walled(it.kind, it.detail)
            }
            val login = buttonWithText(state, "Log in")
                ?: return SignInResult.Failed(
                    STEP_SUBMIT,
                    "no Log in button on the password page"
                )
            state = act(STEP_SUBMIT) { driver.clickRef(login.ref) }
            state.challenge?.let {
                return SignInResult.Walled(it.kind, it.detail)
            }
            return when (val o = act(STEP_VERIFY_LOGIN) {
                read(true)
            }) {
                is XIdentityRead.Outcome.SignedIn ->
                    if (o.handle.equals(handle, ignoreCase = true)) {
                        SignInResult.SignedIn(o.handle)
                    } else {
                        SignInResult.Failed(
                            STEP_VERIFY_LOGIN,
                            "the browser reads signed in as " +
                                "@${o.handle}, not @$handle"
                        )
                    }
                is XIdentityRead.Outcome.SignedOut ->
                    SignInResult.Failed(
                        STEP_VERIFY_LOGIN,
                        "X still shows signed out after the " +
                            "login — the password may have " +
                            "been refused"
                    )
                is XIdentityRead.Outcome.Unreadable ->
                    SignInResult.Failed(STEP_VERIFY_LOGIN, o.reason)
            }
        } catch (e: StepFailure) {
            return SignInResult.Failed(e.step, e.reason)
        }
    }

    // ---------- switch ----------

    sealed class SwitchResult {
        data class Switched(val handle: String) : SwitchResult()
        data class AlreadyActive(
            val handle: String
        ) : SwitchResult()
        data class NeedsUserLogin(
            val handle: String
        ) : SwitchResult()
        data class Walled(
            val kind: String, val detail: String
        ) : SwitchResult()
        data class Failed(
            val step: String, val reason: String
        ) : SwitchResult()
    }

    /**
     * THE SWITCH: sign-out (verified) then sign-in
     * (verified) — the registry's active entry flips
     * only on the verified reads the caller's wrapper
     * applies along the way, so a switch that fails
     * halfway leaves the registry reading signed OUT,
     * never pointing at an account the browser does
     * not hold. Hers completes autonomously; his stops
     * after the sign-out at [SwitchResult.NeedsUserLogin]
     * for the caller's park. The only stop is a failed
     * step, named.
     */
    fun switchTo(
        driver: Driver, targetHandle: String,
        targetIsHers: Boolean, herPassword: String?,
        read: IdentityRead
    ): SwitchResult {
        when (val o = try {
            read(true)
        } catch (e: Exception) {
            return SwitchResult.Failed(
                STEP_READ, "the sign-in check itself failed"
            )
        }) {
            is XIdentityRead.Outcome.SignedIn ->
                if (o.handle.equals(targetHandle, true)) {
                    return SwitchResult.AlreadyActive(targetHandle)
                }
            is XIdentityRead.Outcome.Unreadable ->
                return SwitchResult.Failed(STEP_READ, o.reason)
            is XIdentityRead.Outcome.SignedOut -> Unit
        }
        when (val so = signOut(driver, read)) {
            is SignOutResult.Failed ->
                return SwitchResult.Failed(so.step, so.reason)
            else -> Unit
        }
        if (!targetIsHers) {
            return SwitchResult.NeedsUserLogin(targetHandle)
        }
        val password = herPassword
            ?: return SwitchResult.Failed(
                "sign in as @$targetHandle",
                "her vault password is not on this device"
            )
        return when (
            val si = signIn(driver, targetHandle, password, read)
        ) {
            is SignInResult.SignedIn ->
                SwitchResult.Switched(si.handle)
            is SignInResult.Walled ->
                SwitchResult.Walled(si.kind, si.detail)
            is SignInResult.Failed ->
                SwitchResult.Failed(si.step, si.reason)
        }
    }
}
