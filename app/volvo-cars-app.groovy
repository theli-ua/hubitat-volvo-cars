/*
 * Volvo Cars - Hubitat App
 *
 * Description: Manages connection and communication with Volvo Cars API.
 * Handles authentication, token management, and device creation.
 */
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

String appName() { return 'Volvo Cars' }
String appVersion() { return '0.4.1' }
String nameSpace() { return 'brianschmitt' }

definition(
    name: appName(),
    namespace: nameSpace(),
    author: 'Brian Schmitt',
    description: 'Connects Hubitat to Volvo Cars API',
    category: 'Vehicle',
    iconUrl: '',
    iconX2Url: '',
    version: appVersion(),
    installOnOpen: true,
    singleInstance: true,
    documentationLink: 'https://community.hubitat.com/t/release-volvo-cars-integration-official-api-for-hubitat/153428',
    importUrl: 'https://raw.githubusercontent.com/brianschmitt/hubitat-volvo-cars/main/app/volvo-cars-app.groovy',
)

preferences {
    page(name: 'mainPage')
    page(name: 'authPage')
    page(name: 'otpPage')
    page(name: 'addVehiclePage')
    page(name: 'removeVehiclePage')
}

// Volvo disabled the public OAuth client `h4Yf0b` on 2026-10-26, which took down
// every integration that signed in with it. The Volvo Cars app now uses
// `mo2qy9h_10`: a public client (no secret) that requires Authorization Code +
// PKCE and receives the code through the `vca://` app scheme. Volvo ID itself is
// now Keycloak (realm `volvocars`), brokered by PingFederate on volvoid.
String authClientId() { return 'mo2qy9h_10' }
String authRedirectUri() { return 'vca://www.volvocars.com/l/vca/oauth/callback' }
String authApiUrl() { return 'https://volvoid.eu.volvocars.com/as/authorization.oauth2' }
String tokenUrl() { return 'https://volvoid.eu.volvocars.com/as/token.oauth2' }
String revokeTokenUrl() { return 'https://volvoid.eu.volvocars.com/as/revoke_token.oauth2' }
String apiBaseUrl() { return 'https://api.volvocars.com' }

// Energy v2 needs energy:state:read and the location endpoint needs
// location:read. Both were rejected by the retired h4Yf0b client; mo2qy9h_10
// accepts them.
List<String> authScopeList() {
    return [
        'openid',
        'conve:brake_status',
        'conve:climatization_start_stop',
        'conve:command_accessibility',
        'conve:commands',
        'conve:diagnostics_engine_status',
        'conve:diagnostics_workshop',
        'conve:doors_status',
        'conve:engine_status',
        'conve:fuel_status',
        'conve:honk_flash',
        'conve:lock',
        'conve:lock_status',
        'conve:navigation',
        'conve:odometer_status',
        'conve:trip_statistics',
        'conve:tyre_status',
        'conve:unlock',
        'conve:vehicle_relation',
        'conve:warnings',
        'conve:windows_status',
        'energy:battery_charge_level',
        'energy:charging_connection_status',
        'energy:charging_system_status',
        'energy:electric_range',
        'energy:estimated_charging_time',
        'energy:recharge_status',
        'energy:state:read',
        'location:read',
    ]
}

void installed() {
    logInfo("Installing ${appName()} v${appVersion()}")
    initialize()
}

void updated() {
    logInfo("Updating ${appName()} v${appVersion()}")
    unschedule()
    initialize()
}

void uninstalled() {
    logInfo("Uninstalling ${appName()}")
    clearTokens()
    getAllChildDevices().each { x -> deleteChildDevice(x.deviceNetworkId) }
}

void initialize() {
    app.updateSetting('refreshIntervalMinutes', [type:'number', value: (settings.refreshIntervalMinutes ?: '10')])

    scheduleDeviceRefresh()
    scheduleTokenRefresh()

    app.updateSetting('logEnable', [type:'bool', value: (settings.logEnable ?: false)])
    if (settings.logEnable) { runIn(1800, 'debugLogOff') }
}

def mainPage() {
    dynamicPage(name: 'mainPage', install: true, uninstall: true) {
        section {
            paragraph "Status: <b>${atomicState.accessToken ? 'Authenticated' : 'Not Authenticated'}</b>"
            href 'authPage',
                title: 'Manage Credentials & API Key',
                description: 'Enter or update your credentials and VCC API Key.'
        }
        section('Connected Vehicles') {
            href 'addVehiclePage', title: 'Manage Volvo Vehicles', description: 'Add or remove a vehicle using its VIN.'
        }
        section('Settings') {
            input 'refreshIntervalMinutes',
                'enum',
                title: 'Vehicle Data Refresh Interval (minutes)',
                defaultValue: 10,
                submitOnChange: true,
                options: ['1': '1 minute', '5': '5 minutes', '10': '10 minutes', '15': '15 minutes', '30': '30 minutes']
            input 'logEnable',
                'bool',
                title: 'Enable debug logging',
                defaultValue: false,
                submitOnChange: true
        }
    }
}

def authPage() {
    String nextPage = atomicState.accessToken ? 'mainPage' : 'otpPage'
    dynamicPage(name: 'authPage', title: 'Volvo Credentials & API Key', uninstall: false, nextPage: nextPage) {
        if (atomicState.accessToken) {
            section('Current Token') {
                paragraph "Token expires: ${formatExpiry(atomicState.tokenExpiresAt)}"
                input 'refreshTokenNow', 'button', title: 'Refresh Token Now'
            }
            section('Clear Authentication') {
                paragraph 'Revoke authentication data and tokens. Warning: This will stop all vehicle data updates.'
                input 'clearAuthentication', 'button', title: 'Revoke Authentication'
            }
        } else {
            section('User Authentication') {
                input('username',
                    'email',
                    title: 'Volvo Username (Email)',
                    required: true,
                    defaultValue: settings.username,
                    submitOnChange: true)
                input('password',
                    'password',
                    title: 'Volvo Password',
                    required: true,
                    submitOnChange: true)
                paragraph(
                    "<a href='https://developer.volvocars.com/account/#your-api-applications', target='_blank'>"
                    + 'Link to Volvo for Api Key</a>')
                input('vccApiKey',
                    'text',
                    title: 'Volvo VCC API Key',
                    required: true,
                    defaultValue: settings.vccApiKey,
                    submitOnChange: true)
                if (settings.username && settings.password && settings.vccApiKey) {
                    app.removeSetting('otpCode')
                    authenticate()
                }
            }
        }

        section('Already Have A Token?') {
            paragraph('Signing in above is the normal route. It is only worth reaching for this if Volvo breaks '
                + 'the login page again: tools/volvo_auth_probe.py can mint a refresh token from a browser '
                + 'login, and pasting it here keeps vehicles updating. Cleared from settings once used.')
            input 'manualRefreshToken', 'text', title: 'Refresh token', required: false, submitOnChange: true
            if (settings.manualRefreshToken) {
                input 'useManualToken', 'button', title: 'Use Pasted Refresh Token'
            }
        }

        if (atomicState.authError) {
            section('Authentication Error') {
                paragraph "${atomicState.authError}"
            }
        }
    }
}

def otpPage() {
    dynamicPage(name: 'otpPage', title: 'Volvo Credentials & API Key', uninstall: false, 'nextPage': 'mainPage') {
        if (atomicState.accessToken) {
            section('OTP Authentication') {
                paragraph 'Completed authentication. No OTP currently required.'
            }
        } else {
            section('OTP Authentication') {
                paragraph(
                    'Volvo emails a 6-digit code once your password is accepted. Enter it below to finish signing in.')
                input 'otpCode', 'text', title: 'OTP Code', required: true, submitOnChange: true
            }

            if (settings.otpCode && settings.otpCode.length() == 6 && !atomicState.accessToken) {
                submitOtp()
            }

            if (atomicState.authError) {
                if (!atomicState.otpAction) {
                    section('New OTP Required') {
                        input 'authenticate', 'button', title: 'Request New OTP'
                    }
                }
                section('Authentication Error') {
                    paragraph "${atomicState.authError}"
                }
            }
        }
    }
}

def addVehiclePage() {
    dynamicPage(name: 'addVehiclePage', title: 'Manage Vehicles', uninstall: false, nextPage: 'mainPage') {
        section {
            if (atomicState.accessToken) {
                app.removeSetting('otpCode')
                // Fetch the list of vehicles
                def vehicles = getVehicles().data
                def vinOptions = [:]
                if (vehicles?.size() > 0) {
                    vehicles.each { vehicle ->
                        def vin = vehicle.vin
                        def friendlyName = "VIN ${vin}"
                        vinOptions[vin] = friendlyName
                    }
                }

                input 'vinToAdd', 'enum', title: 'Select Vehicle to add:', options: vinOptions, submitOnChange: true

                if (settings.vinToAdd) {
                    input 'addThisVehicle', 'button', title: 'Add Vehicle'
                }
                if (atomicState.vehicleError) {
                    section('Error') {
                        paragraph "${atomicState.vehicleError}"
                    }
                    atomicState.remove('vehicleError')
                }
            } else {
                paragraph 'Please authenticate on the main page first.'
            }
        }
        section('Existing Vehicles:') {
            List<com.hubitat.app.ChildDeviceWrapper> childDevices = getChildDevices()
            if (childDevices?.size() > 0) {
                childDevices.each { child ->
                    def vin = child.deviceNetworkId
                    def friendlyName = child.displayName
                    href(
                        name: "removeVehicle_${vin}",
                        title: "Remove ${friendlyName} (${vin})",
                        description: 'Remove this vehicle and its device',
                        params: [vinToRemove: vin],
                        page: 'removeVehiclePage'
                    )
                }
            } else {
                paragraph 'No vehicles added yet.'
            }
        }
    }
}

def removeVehiclePage(Map params) {
    dynamicPage(name: 'removeVehiclePage', title: 'Remove Vehicle', uninstall: false) {
        def vin = params?.vinToRemove
        if (!vin) {
            section {
                paragraph 'Car has been removed.'
            }
            return
        }

        app.updateSetting('vinToRemove', vin)

        section {
            paragraph "Are you sure you want to remove the vehicle with VIN: ${vin}?"
            input 'confirmRemoveVehicle', 'button', title: 'Confirm Removal'
        }

        params.clear()
    }
}

void appButtonHandler(String btnName) {
    logDebug "appButtonHandler called with button: ${btnName}"
    switch (btnName) {
        case 'authenticate':
            authenticate()
            break
        case 'addThisVehicle':
            addThisVehicle()
            break
        case 'refreshTokenNow':
            refreshTokenNow()
            break
        case 'confirmRemoveVehicle':
            removeVehicle()
            break
        case 'useManualToken':
            adoptManualRefreshToken()
            break
        case 'clearAuthentication':
            atomicState.remove('username')
            atomicState.remove('vccApiKey')
            clearAuthFlow()
            clearTokens()
            break
        default:
            logWarn "Unhandled button pressed in appButtonHandler: ${btnName}"
            break
    }
}

void removeVehicle() {
    String vin = app.getSetting('vinToRemove')
    logDebug "Removing vehicle: ${vin}"
    com.hubitat.app.ChildDeviceWrapper child = getChildDevice(vin)
    if (child) {
        try {
            deleteChildDevice(vin)
        } catch (e) {
            logError "Error removing child device ${vin}: ${e.message}"
        }
    }
    app.removeSetting('vinToRemove')
    scheduleDeviceRefresh()
}

void addThisVehicle() {
    def vin = app.getSetting('vinToAdd')?.trim()?.toUpperCase()

    if (!vin || vin.size() != 17) {
        logWarn "Invalid VIN provided: ${vin}"
        atomicState.vehicleError = 'No VIN provided or invalid VIN length.'
        return
    }

    com.hubitat.app.ChildDeviceWrapper d = getChildDevice(vin)
    if (d) {
        logWarn "Device with DNI ${vin} already exists."
        atomicState.vehicleError = "Device with VIN ${vin} already exists."
        return
    }

    try {
        Map data = getVehicleData(vin, ['vehicle'])
        Map vehicleData = data['vehicle'].data
        logDebug "Adding vehicle: ${vehicleData} (${vin})"
        String label = "Volvo ${vehicleData.descriptions.model}"
        addChildDevice(nameSpace(), "${appName()} Device", vin, [label: label, name: label])
        logInfo "Added Volvo Car Device: ${label} (${vin})"
        runIn(2, refreshVehicleData, [data: [vin: vin]])
        } catch (e) {
        logError "Error adding child device ${vin}: ${e.message}"
    }

    app.removeSetting('vinToAdd')
    scheduleDeviceRefresh()
}

void authenticate() {
    logDebug 'Starting Volvo ID login (Keycloak + PKCE)...'
    atomicState.remove('authError')
    clearAuthFlow()

    String verifier = newPkceVerifier()
    String challenge
    try {
        challenge = pkceChallenge(verifier)
    } catch (e) {
        handleAuthError("Could not build the PKCE challenge - the sandbox may be blocking SHA-256: ${e.message}")
        return
    }
    atomicState.pkceVerifier = verifier
    Map jar = newCookieJar()

    Map query = [
        client_id: authClientId(),
        redirect_uri: authRedirectUri(),
        response_type: 'code',
        scope: authScopeList().join(' '),
        code_challenge: challenge,
        code_challenge_method: 'S256',
        acr_values: 'urn:volvoid:aal:bronze:2sv',
    ]

    Map loginPage = authFollow('GET', authApiUrl() + '?' + formEncode(query), null, jar)
    Map login = parseKcContext(loginPage.html)
    if (login.pageId != 'login' || !login.loginAction) {
        saveCookieJar(jar)
        handleAuthError("Could not load the Volvo ID login page${login.message ? ": ${login.message}" : ''}",
                        describeHttp(loginPage))
        return
    }
    if ((loginPage.requests ?: 1) == 1) {
        // A single response that already holds the page means Hubitat followed the
        // authorize redirect by itself, so those cookies belong to Keycloak.
        rekeyJarToPageHost(jar, authApiUrl(), login.loginAction)
    }

    logDebug 'Volvo ID login page loaded, submitting credentials'
    Map otpPage = authFollow('POST', login.loginAction,
                             [username: settings.username, password: settings.password, credentialId: ''], jar)
    Map otp = parseKcContext(otpPage.html)
    if (otp.pageId != 'login-otp-email' || !otp.loginAction) {
        saveCookieJar(jar)
        handleAuthError("Volvo did not send an OTP code${otp.message ? ": ${otp.message}" : ''}",
                        describeHttp(otpPage))
        return
    }

    atomicState.otpAction = otp.loginAction
    saveCookieJar(jar)
    logInfo 'Volvo has emailed a one-time code to this Volvo ID. Enter it to finish signing in.'
}

def fixHttpUrl(String url) {
    if (!url) {
        return url
    }
    if (url.startsWith('http://')) {
        return url.replace('http://', 'https://')
    }
    return url
}

void submitOtp() {
    logDebug 'Submitting OTP...'
    atomicState.remove('authError')

    String otpAction = atomicState.otpAction
    if (!otpAction) {
        handleAuthError('That login session has ended. Save your credentials, or press "Request New OTP".')
        return
    }

    Map jar = loadCookieJar()
    Map result = authFollow('POST', fixHttpUrl(otpAction), [otp: settings.otpCode.trim()], jar)

    if (result.code) {
        logDebug "Authorization code received (length ${result.code.length()})"
        app.removeSetting('otpCode')
        saveCookieJar(jar)
        requestToken(result.code)
        return
    }

    Map otp = parseKcContext(result.html)
    if (otp.pageId == 'login-otp-email' && otp.loginAction) {
        // Volvo re-serves the OTP form with a fresh action for each attempt.
        atomicState.otpAction = otp.loginAction
        saveCookieJar(jar)
        handleAuthError("Volvo rejected the OTP code${otp.message ? ": ${otp.message}" : ''}")
        return
    }

    app.removeSetting('otpCode')
    clearAuthFlow()
    handleAuthError("Sign-in failed${otp.message ? ": ${otp.message}" : (result.error ? ": ${result.error}" : '')}"
                    + '. Enter your credentials again to restart it.', describeHttp(result))
}

void refreshTokenNow() {
    logDebug 'Manual token refresh requested.'
    refreshToken()
}

// Recovery path if Volvo breaks this login the way they broke the previous one:
// a refresh token obtained out of band is adopted and the app continues normally.
void adoptManualRefreshToken() {
    String token = settings.manualRefreshToken?.trim()
    app.removeSetting('manualRefreshToken')
    if (!token) {
        handleAuthError('No refresh token was pasted.')
        return
    }
    logInfo 'Adopting a manually supplied refresh token.'
    clearAuthFlow()
    atomicState.refreshToken = token
    atomicState.remove('authError')
    refreshToken()
}

void sendCommand(String vin, String command, Map body = null) {
    logInfo "Device requested command '${command}' for VIN ${vin}"
    if (!atomicState.accessToken) { return }
    if (now() >= atomicState.tokenExpiresAt) { return }

    Map commandEndpoints = [
        'lock': "/connected-vehicle/v2/vehicles/${vin}/commands/lock",
        'unlock': "/connected-vehicle/v2/vehicles/${vin}/commands/unlock",
        'climatization-start': "/connected-vehicle/v2/vehicles/${vin}/commands/climatization-start",
        'climatization-stop': "/connected-vehicle/v2/vehicles/${vin}/commands/climatization-stop",
        'engine-start': "/connected-vehicle/v2/vehicles/${vin}/commands/engine-start",
        'engine-stop': "/connected-vehicle/v2/vehicles/${vin}/commands/engine-stop",
        'honk': "/connected-vehicle/v2/vehicles/${vin}/commands/honk",
        'flash': "/connected-vehicle/v2/vehicles/${vin}/commands/flash",
        'honk-flash': "/connected-vehicle/v2/vehicles/${vin}/commands/honk-flash",
        'lock-guard': "/connected-vehicle/v2/vehicles/${vin}/commands/lock-reduced-guard",
    ]

    String endpointPath = commandEndpoints[command]
    if (!endpointPath) {
        logError "Unknown command requested: ${command}"
        return
    }

    String url = apiBaseUrl() + endpointPath

    params = [
        uri: url,
        headers: apiHeaders(),
        body: (body ?: [:]),
        contentType: 'application/json',
        timeout: 60
    ]

    logDebug "Sending command '${command}' to ${url} with body: ${params.body}"
    try {
        asynchttpPost('processCommandCallBack', params, [command: command])
    } catch (e) {
        logError "Exception sending command '${command}' for VIN ${vin}: ${e.message}"
    }
}

def processCommandCallBack(resp, data) {
    if (resp.status == 200 || resp.status == 202) {
        logDebug "Command '${data['command']}' for VIN ${resp.json.data.vin}. Status: ${resp.json.data.invokeStatus}"
        result = resp.json
    } else {
        logError "Command '${command}' failed for VIN ${vin}. Status: ${resp.status}"
        result = [error: "API Error ${resp.status}"]
    }
}

void refreshVehicleData(Map params) {
    String vin = params.vin
    if (!vin) {
        logWarn 'refreshVehicleData called without VIN'
        return
    }
    logDebug "Refreshing data for VIN: ${vin}"
    com.hubitat.app.ChildDeviceWrapper child = getChildDevice(vin)
    if (!child) {
        logWarn "Device not found for VIN: ${vin}"
        return
    }

    Map apiData = getVehicleData(vin)
    if (apiData.error) {
        logDebug "Failed to get data for ${vin}: ${apiData.error}"
        child.parse([error: apiData.error])
    } else {
        child.parse(apiData)
    }
}

void refreshToken() {
    logDebug 'Refreshing token...'
    if (!atomicState.refreshToken) {
        logDebug 'No refresh token available. Please re-authenticate.'
        handleAuthError('Refresh token missing.')
        return
    }

    Map payload = [
        refresh_token: atomicState.refreshToken,
        grant_type: 'refresh_token',
        client_id: authClientId(),
    ]
    Map headers = tokenHeaders()

    try {
        httpPost([
                uri: tokenUrl(),
                body: payload,
                headers: headers,
                requestContentType: 'application/x-www-form-urlencoded',
                contentType : 'application/json'])
        { resp ->
            if (resp.status == 200) {
                storeTokens(resp.data)
                atomicState.remove('authError')
                logDebug 'Successfully refreshed tokens.'
             } else {
                handleAuthError("Token refresh failed. Status: ${resp.status}", snippet(resp.data))
                clearTokens()
            }
        }
    } catch (e) {
        handleAuthError("Exception during token refresh: ${e.message}")
        clearTokens()
    }
}

void debugLogOff() {
    app.updateSetting('logEnable', [value:'false', type:'bool'])
}

private void storeTokens(Map tokenData, boolean fromLogin = false) {
    logDebug "Token data: ${tokenData}"
    atomicState.accessToken = tokenData.access_token
    atomicState.refreshToken = tokenData.refresh_token ?: atomicState.refreshToken
    int expiresIn = tokenData.expires_in.toInteger()
    atomicState.tokenExpiresAt = now() + (expiresIn * 1000)
    scheduleTokenRefresh(expiresIn)
    atomicState.remove('consecutiveOtpVerified')
    if (fromLogin) {
        // A sign-in is worth announcing; the hourly refresh underneath it is not.
        logInfo "Signed in to Volvo ID. Access token valid until ${formatExpiry(atomicState.tokenExpiresAt)}."
    } else {
        logDebug "Tokens stored. Access token expires at ${formatExpiry(atomicState.tokenExpiresAt)}"
    }
    logDebug "Token data: ${tokenData}"

    if (settings.vccApiKey != app.getSetting('vccApiKey')) {
        app.updateSetting('vccApiKey', settings.vccApiKey)
    }
}

// ---------------------------------------------------------------------------
// Login transport.
//
// Sign-in crosses two hosts (volvoid.eu.volvocars.com, then auth.volvocars.com)
// with redirects between them, and every hop sets cookies the next hop needs.
// Hops are followed by hand so the jar stays visible. If Hubitat's own HTTP
// client follows a hop instead, that is tolerated: the only hop it cannot
// follow is the `vca://` app callback, and when that surfaces as an exception
// the authorization code is recovered from the exception text.
// ---------------------------------------------------------------------------

private Map authFollow(String method, String startUrl, Map form, Map jar) {
    String url = startUrl
    int requests = 0
    for (int hop = 0; hop < 8; hop++) {
        url = fixHttpUrl(url)
        Map resp = authRequest(method, url, form, jar)
        requests++
        resp.requests = requests
        logDebug "auth hop ${hop + 1}: ${method} ${scrubUrl(url)} -> status ${resp.status}"

        if (resp.code) {
            return resp
        }
        if (resp.error && !resp.html) {
            return resp
        }
        if (resp.status >= 300 && resp.status < 400 && resp.location) {
            if (resp.location.startsWith('vca://')) {
                Map callback = parseCallbackUrl(resp.location)
                if (!callback.code) {
                    logError "Volvo callback carried no authorization code: ${scrubUrl(resp.location)}"
                }
                return callback
            }
            url = absoluteUrl(resp.location, url)
            method = 'GET'
            form = null
            continue
        }
        if (resp.status >= 400) {
            resp.error = "HTTP ${resp.status} from ${urlHost(url)}"
            return resp
        }
        return resp
    }
    return [error: 'Too many redirects while signing in to Volvo ID']
}

private Map authRequest(String method, String url, Map form, Map jar) {
    Map result = [status: 0, location: null, html: null, error: null, code: null]

    Map headers = [
        'User-Agent': 'vca-android/123',
        'Accept': 'text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8',
        'Accept-Language': 'en-US,en;q=0.9',
    ]
    String cookies = cookieHeader(url, jar)
    if (cookies) {
        headers['Cookie'] = cookies
    }
    result.sentCookies = cookies ? cookies.split(';').collect { it.trim().split('=')[0] }.join(',') : 'none'

    Map params = [uri: url, headers: headers, textParser: true, timeout: 30, followRedirects: false]
    if (form != null) {
        params.body = form
        params.requestContentType = 'application/x-www-form-urlencoded'
    }

    try {
        if (form == null) {
            httpGet(params) { resp -> captureAuthResponse(resp, url, result, jar) }
        } else {
            httpPost(params) { resp -> captureAuthResponse(resp, url, result, jar) }
        }
    } catch (e) {
        // Non-2xx responses and unfollowable `vca://` redirects both land here.
        // Volvo's response body is not exposed on either, but the exception text
        // often contains the callback URL the client failed to follow.
        String message = e.message ?: e.toString()
        logDebug "auth request exception: ${message}"
        Map callback = parseCallbackUrl(message)
        if (callback.code) {
            logDebug 'Recovered the authorization code from the exception text'
            result.code = callback.code
        } else {
            result.error = message
        }
    }
    return result
}

private void captureAuthResponse(resp, String url, Map result, Map jar) {
    result.status = resp.status
    List<String> setCookies = rawSetCookies(resp)
    storeCookies(url, setCookies, jar)
    result.receivedCookies = cookieNames(setCookies)
    def location = resp.headers?.location ?: resp.headers?.Location
    if (location) {
        result.location = location.toString()
    }
    result.html = bodyText(resp.data)
    result.bytes = result.html ? result.html.length() : 0
    result.type = dataKind(resp.data)
}

// Describe the body shape without reflection: Hubitat's sandbox rejects
// getClass(), which aborts the whole script.
private String dataKind(def data) {
    if (data == null) {
        return 'null'
    }
    if (data instanceof CharSequence) {
        return 'text'
    }
    if (data instanceof Map) {
        return "map[${((Map) data).keySet().take(4).join(', ')}]"
    }
    try {
        if (data.text != null && data.length instanceof Number) {
            return 'text-wrapper'
        }
    } catch (ignored) {
        // not a text wrapper
    }
    return 'object'
}

// One-line description of a failed step. Hubitat's debug logging auto-disables
// after 30 minutes, and an auth failure is rare and important enough that the
// detail must be visible without it.
private String describeHttp(Map resp, int maxChars = 400) {
    if (!resp) {
        return null
    }
    StringBuilder sb = new StringBuilder()
    sb.append('status=').append(resp.status ?: 'n/a')
    sb.append(' bytes=').append(resp.bytes ?: 0)
    sb.append(' location=').append(scrubUrl(resp.location) ?: '-')
    sb.append(' error=').append(resp.error ?: '-')
    sb.append(' type=').append(resp.type ?: '-')
    sb.append(' sentCookies=').append(resp.sentCookies ?: '-')
    sb.append(' setCookies=').append(resp.receivedCookies ?: '-')
    sb.append(' body=').append(snippet(resp.html, maxChars) ?: '-')
    return sb.toString()
}

// ---------------------------------------------------------------------------
// Keycloak page reading.
//
// Volvo ID is Keycloak (realm `volvocars`). The login page is a themed template
// that carries its state in a `kcContext` object rather than plain form fields;
// it also embeds JavaScript, so it is not parseable as JSON - the three values
// this flow needs are pulled out individually.
// ---------------------------------------------------------------------------

private Map parseKcContext(String html) {
    if (!html) {
        return [pageId: null, loginAction: null, message: null]
    }
    return [
        pageId: kcValue(html, 'pageId'),
        loginAction: kcValue(html, 'loginAction'),
        message: kcValue(html, 'summary'),
    ]
}

private String kcValue(String html, String key) {
    def matcher = (html =~ /"(${key})"\s*:\s*"((?:[^"\\]|\\.)*)"/)
    if (!matcher.find()) {
        return null
    }
    String value = matcher.group(2)
    value = value.replace('\\/', '/').replace('\\u002F', '/')
    value = value.replace('\\u0026', '&').replace('\\u003d', '=')
    return value.replace('&amp;', '&')
}

// ---------------------------------------------------------------------------
// Cookie jar.
//
// Set-Cookie Path/Domain attributes are deliberately ignored: the Keycloak
// cookies are host-only on auth.volvocars.com under /realms/volvocars/, and
// every request this flow makes to that host is under that path.
// ---------------------------------------------------------------------------

private Map newCookieJar() {
    return [:]
}

private Map loadCookieJar() {
    def jar = atomicState.authCookieJar
    return (jar instanceof Map) ? new LinkedHashMap(jar) : newCookieJar()
}

private void saveCookieJar(Map jar) {
    atomicState.authCookieJar = jar
    logDebug "Cookie jar: ${jar.collect { host, cookies -> "${host}=[${cookies.keySet().join(', ')}]" }.join(' ')}"
}

private void storeCookies(String url, List<String> items, Map jar) {
    if (!items) {
        return
    }
    String host = urlHost(url)
    Map hostJar = (jar[host] instanceof Map) ? jar[host] : [:]
    items.each { String item ->
        String pair = item.split(';')[0]
        int eq = pair.indexOf('=')
        if (eq <= 0) {
            return
        }
        String name = pair.substring(0, eq).trim()
        String value = pair.substring(eq + 1)
        if (value.length() == 0) {
            hostJar.remove(name)
        } else {
            hostJar[name] = value
        }
    }
    jar[host] = hostJar
}

// Hubitat exposes only the last value of a repeated header through
// resp.headers['Set-Cookie']. The sync client's getHeaders(name) returns them
// all - each prefixed with 'Set-Cookie: ', which stripCookieName removes. Using
// the wrong accessor silently drops every cookie but the last one, which is how
// Keycloak's AUTH_SESSION_ID (set first of three) disappeared.
private List<String> rawSetCookies(resp) {
    List<String> out = []
    try {
        def many = resp.getHeaders('Set-Cookie')
        if (many instanceof List) {
            many.each { out << stripCookieName(it.toString()) }
        } else if (many instanceof Object[]) {
            many.each { out << stripCookieName(it.toString()) }
        } else if (many) {
            // Hubitat returns one bracketed string: "[Set-Cookie: A=1;Path=/, Set-Cookie: B=2;Path=/]".
            splitJoinedCookies(many.toString()).each { out << stripCookieName(it) }
        }
    } catch (ignored) {
        // getHeaders unavailable on this response type
    }
    if (out) {
        return out.findAll { it?.trim() }
    }
    try {
        def one = resp.headers?.'Set-Cookie' ?: resp.headers?.'set-cookie'
        if (one) {
            splitJoinedCookies(one.toString()).each { out << stripCookieName(it) }
        }
    } catch (ignored) {
        // no Set-Cookie header
    }
    return out.findAll { it?.trim() }
}

private String stripCookieName(String value) {
    return value.trim().replaceFirst(/(?i)^set-cookie:\s*/, '')
}

// Several Set-Cookie headers can arrive as one string. Unwrap list brackets, then
// split on ', ' at a cookie boundary: prefer the 'Set-Cookie:' marker, otherwise
// `name=` (an Expires attribute's comma is followed by a date, never a name).
private List<String> splitJoinedCookies(String joined) {
    String text = joined.trim()
    if (text.startsWith('[') && text.endsWith(']')) {
        text = text.substring(1, text.length() - 1)
    }
    List<String> parts
    if (text =~ /(?i)set-cookie:/) {
        parts = text.split(/(?i),\s*(?=set-cookie:)/)
    } else {
        parts = text.split(/,\s*(?=[A-Za-z0-9_.\-]+=)/)
    }
    return parts.findAll { it?.trim() }.collect { stripCookieName(it.toString()) }
}

private String cookieNames(List<String> setCookies) {
    if (!setCookies) {
        return 'none'
    }
    return setCookies.collect { it.split(';')[0].split('=')[0] }.join(',')
}
private String cookieHeader(String url, Map jar) {
    Map hostJar = jar[urlHost(url)]
    if (!hostJar) {
        return null
    }
    return hostJar.collect { name, value -> "${name}=${value}" }.join('; ')
}

// When Hubitat follows a redirect across hosts by itself, the cookies on that
// response belong to the host that served the page, not to the host that was
// asked. Keycloak's session cookie is host-only, so sending it back under the
// wrong host means the next POST arrives with no session at all.
private void rekeyJarToPageHost(Map jar, String requestedUrl, String pageActionUrl) {
    String requestedHost = urlHost(requestedUrl)
    String pageHost = pageActionUrl ? urlHost(pageActionUrl) : null
    if (!pageHost || pageHost == requestedHost) {
        return
    }
    Map moved = (jar[requestedHost] instanceof Map) ? jar[requestedHost] : null
    if (!moved) {
        return
    }
    Map target = (jar[pageHost] instanceof Map) ? jar[pageHost] : [:]
    moved.each { name, value ->
        if (!target.containsKey(name)) {
            target[name] = value
        }
    }
    jar[pageHost] = target
    jar.remove(requestedHost)
    logDebug "Response came from ${pageHost}, cookies re-keyed from ${requestedHost}"
}

// ---------------------------------------------------------------------------
// URL and PKCE helpers. java.net.URLEncoder is avoided to keep this inside the
// Hubitat sandbox; PKCE needs SHA-256 and base64url only.
// ---------------------------------------------------------------------------

private String urlHost(String url) {
    String host = url.replaceFirst('^https?://', '')
    int cut = host.indexOf('/')
    if (cut < 0) {
        cut = host.indexOf('?')
    }
    if (cut >= 0) {
        host = host.substring(0, cut)
    }
    return host.toLowerCase()
}

private String absoluteUrl(String location, String base) {
    String target = location.trim()
    if (target.startsWith('http://') || target.startsWith('https://') || target.startsWith('vca://')) {
        return target
    }
    int schemeAt = base.indexOf('://')
    if (target.startsWith('//') && schemeAt > 0) {
        return base.substring(0, schemeAt + 1) + target
    }
    if (target.startsWith('/') && schemeAt > 0) {
        int pathAt = base.indexOf('/', schemeAt + 3)
        String schemeHost = pathAt > 0 ? base.substring(0, pathAt) : base
        return schemeHost + target
    }
    int queryAt = base.indexOf('?')
    String pathPart = queryAt > 0 ? base.substring(0, queryAt) : base
    return pathPart.substring(0, pathPart.lastIndexOf('/') + 1) + target
}

private Map parseCallbackUrl(String text) {
    Map out = [code: null, error: null]
    if (!text) {
        return out
    }
    def matcher = (text =~ /[?&](code|error|error_description)=([^&"'\\\s<>]+)/)
    while (matcher.find()) {
        String key = matcher.group(1)
        String value = matcher.group(2)
        if (key == 'code') {
            out.code = value
        } else if (!out.error) {
            out.error = value
        }
    }
    return out
}

private String formEncode(Map params) {
    return params.collect { key, value -> "${pctEncode(key.toString())}=${pctEncode(value.toString())}" }.join('&')
}

private String pctEncode(String value) {
    StringBuilder out = new StringBuilder()
    value.getBytes('UTF-8').each { byte b ->
        int c = b & 0xFF
        boolean safe = (c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A) || c == 0x2D || c == 0x2E || c == 0x5F || c == 0x7E
        if (safe) {
            out.append((char) c)
        } else {
            out.append('%').append(String.format('%02X', c))
        }
    }
    return out.toString()
}

private String newPkceVerifier() {
    // 64 hex chars is a valid code_verifier (43-128 unreserved). UUID is backed by
    // a secure random source and, unlike java.security.SecureRandom, is known to
    // be reachable from the Hubitat sandbox.
    String verifier = java.util.UUID.randomUUID().toString().replace('-', '')
    verifier = verifier + java.util.UUID.randomUUID().toString().replace('-', '')
    return verifier
}

private String pkceChallenge(String verifier) {
    byte[] digest = java.security.MessageDigest.getInstance('SHA-256').digest(verifier.getBytes('UTF-8'))
    return base64Url(digest)
}

private String base64Url(byte[] data) {
    String encoded = data.encodeBase64().toString().replaceAll('\\s', '')
    return encoded.replace('+', '-').replace('/', '_').replaceAll('=+$', '')
}

private String scrubUrl(String url) {
    if (!url) {
        return null
    }
    return url.replaceAll('(code|state)=[^&]*', '$1=<hidden>')
}

private void clearAuthFlow() {
    atomicState.remove('otpAction')
    atomicState.remove('pkceVerifier')
    atomicState.remove('authCookieJar')
    atomicState.remove('otpUrl')
    atomicState.remove('authCookies')
    atomicState.remove('consecutiveOtpVerified')
}

private String bodyText(def data) {
    if (data == null) {
        return null
    }
    if (data instanceof CharSequence) {
        return data.toString()
    }
    // A text body comes back as a [length, text] wrapper - as a map in some calls
    // and as a wrapper object in others, so unwrap it by property rather than by
    // type. Re-serialising the wrapper escapes every quote in the page and breaks
    // the kcContext regex.
    if (data instanceof Map && data.containsKey('text')) {
        return data['text']?.toString()
    }
    try {
        def wrapperText = data.text
        def wrapperLength = data.length
        if (wrapperText != null && wrapperLength instanceof Number) {
            return wrapperText.toString()
        }
    } catch (ignored) {
        // not a text wrapper
    }
    return JsonOutput.toJson(data)
}

// Volvo returns whole HTML pages where an error body is expected; keep the log
// readable and never write a session code into it.
private String snippet(def text, int maxChars = 600) {
    String value = bodyText(text)
    if (!value) {
        return null
    }
    value = value.replaceAll('(?s)\\s+', ' ').replaceAll('(code|session_code|state)=[^&"\\s]*', '$1=<hidden>')
    return value.length() > maxChars ? value.substring(0, maxChars) + '...' : value
}

private void requestToken(String authCode) {
    logDebug 'Exchanging the authorization code for tokens...'

    String verifier = atomicState.pkceVerifier
    if (!verifier) {
        handleAuthError('The PKCE verifier for this login is missing. Enter your credentials again to restart it.')
        return
    }

    Map payload = [
        grant_type: 'authorization_code',
        code: authCode,
        redirect_uri: authRedirectUri(),
        client_id: authClientId(),
        code_verifier: verifier,
    ]

    try {
        httpPost([
                uri: tokenUrl(),
                body: payload,
                headers: tokenHeaders(),
                requestContentType: 'application/x-www-form-urlencoded',
                contentType: 'application/json'])
        { resp ->
            if (resp.status == 200) {
                storeTokens(resp.data, true)
                atomicState.remove('authError')
                clearAuthFlow()
             } else {
                handleAuthError("Token request failed. Status: ${resp.status}", snippet(resp.data))
            }
        }
    } catch (e) {
        handleAuthError("Exception during token request: ${e.message}")
    }
}

private void clearTokens() {
    logWarn 'Clearing stored tokens due to refresh failure.'
    atomicState.remove('accessToken')
    atomicState.remove('refreshToken')
    atomicState.remove('tokenExpiresAt')
    unschedule('refreshToken')
}

private void handleAuthError(String message, String responseData = null) {
    logError "Authentication Error: ${message}"
    if (responseData) {
        logWarn "Auth failure detail: ${responseData}"
    }
    atomicState.authError = message
}

private void scheduleTokenRefresh(Integer expiresInSeconds = null) {
    unschedule('refreshToken')
    if (atomicState.refreshToken && atomicState.tokenExpiresAt) {
        def refreshDelaySeconds
        if (expiresInSeconds) {
            refreshDelaySeconds = (expiresInSeconds * 0.8).toInteger()
            if (refreshDelaySeconds < 60) { refreshDelaySeconds = 60 }
        } else {
            def nowMillis = now()
            def expiryMillis = atomicState.tokenExpiresAt
            if (expiryMillis > nowMillis) {
                def remainingMillis = expiryMillis - nowMillis
                refreshDelaySeconds = (remainingMillis / 1000 * 0.8).toInteger()
                if (refreshDelaySeconds < 60) { refreshDelaySeconds = 60 }
            } else {
                logWarn 'Token already expired, attempting immediate refresh.'
                refreshToken()
                return
            }
        }

        logDebug "Scheduling token refresh in ${refreshDelaySeconds} seconds."
        runIn(refreshDelaySeconds, refreshToken, [overwrite: true])
    }
}

private void scheduleDeviceRefresh() {
    unschedule('refreshAllVehicleData')
    String interval = settings.refreshIntervalMinutes ?: '10'
    int childDeviceCount = getChildDevices()?.size() ?: 0
    if (childDeviceCount > 0) {
        logInfo "Scheduling vehicle data refresh every ${interval} minutes."
        schedule("0 0/${interval} * ? * *", refreshAllVehicleData)
    } else {
        logInfo 'Vehicle data refresh disabled (interval <= 0 or no vehicles).'
    }
}

private Map getVehicleData(String vin, List<String> endpoints = null) {
    logDebug "App requested data for VIN ${vin}, endpoints: ${endpoints ?: 'all'}"
    if (!atomicState.accessToken) {
        logDebug "API call attempted without access token for VIN ${vin}"
        refreshToken()
        return [error: 'Not authenticated']
    }
    if (now() >= atomicState.tokenExpiresAt) {
        logDebug "API call attempted with expired token for VIN ${vin}"
        refreshToken()
        return [error: 'Token expired, attempting refresh']
    }

    Map results = [:]

    def allEndpoints = [
        //Connected Vehicle API
        'availability': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/command-accessibility",
        'brakes': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/brakes",
        'commands': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/commands",
        'diagnostics': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/diagnostics",
        'doors': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/doors",
        'engineStatus': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/engine-status",
        'engineWarnings': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/engine",
        'odometer': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/odometer",
        'statistics': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/statistics",
        'tyres': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/tyres",
        'warnings': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/warnings",
        'windows': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/windows",
        'vehicle': "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}",
    // Location API
    // TODO: disabling location endpoint for now...
    //'location': "${apiBaseUrl()}/location/v1/vehicles/${vin}/location",
    ]

    def child = getChildDevice(vin)
    def fuelType = child?.currentValue('fuelType') ?: 'UNKNOWN'
    if (fuelType == 'PETROL/ELECTRIC' || fuelType == 'ELECTRIC' || fuelType == 'NONE') {
        allEndpoints['rechargeStatus'] = "${apiBaseUrl()}/energy/v2/vehicles/${vin}/state"
    } else if (fuelType == 'PETROL/ELECTRIC' || fuelType == 'DIESEL' || fuelType == 'PETROL') {
        allEndpoints['fuel'] = "${apiBaseUrl()}/connected-vehicle/v2/vehicles/${vin}/fuel"
    }

    def endpointsToFetch = endpoints ?: allEndpoints.keySet()

    endpointsToFetch.each { endpointKey ->
        if (allEndpoints[endpointKey]) {
            String url = allEndpoints[endpointKey]
            try {
                httpGet([
                        uri: url,
                        headers: apiHeaders(),
                        textParser: true]) // need to use text parser to work around the energy endpoint
                { resp ->
                    if (resp.status == 200) {
                        String responseBodyString = resp.data.text
                        logDebug "Data for ${endpointKey} (${vin}): ${responseBodyString}"
                        def jsonSlurper = new JsonSlurper()
                        def parsedJson = jsonSlurper.parseText(responseBodyString)
                        // Energy v2 returns flat fields without `data` wrapper; normalize it
                        if (!parsedJson.data && parsedJson.batteryChargeLevel) {
                            results[endpointKey] = [data: parsedJson]
                        } else {
                            results[endpointKey] = parsedJson
                        }
                    } else {
                        logWarn "Failed to get data for ${endpointKey} (${vin}). Status: ${resp.status}"
                        results[endpointKey] = [error: "API Error ${resp.status}"]
                    }
                }
                pauseExecution(200)
             } catch (e) {
                logError "Exception getting data for ${endpointKey} (${vin}): ${e.message}"
                results[endpointKey] = [error: "Exception: ${e.message}"]
            }
        } else {
            logWarn "Unknown data endpoint requested: ${endpointKey}"
        }
    }

    return results
}

private Map getVehicles() {
    def url = "${apiBaseUrl()}/connected-vehicle/v2/vehicles"

    try {
        httpGet([uri: url, headers: apiHeaders(), contentType: 'application/json']) { resp ->
            if (resp.status == 200) {
                return resp.data
            }

            logWarn "Failed to get data for ${endpointKey} (${vin}). Status: ${resp.status}"
            results[endpointKey] = [error: "API Error ${resp.status}"]
        }
    } catch (e) {
        logError "Exception getting data for ${url}: ${e.message}"
    }
}

void refreshAllVehicleData() {
    logDebug 'Executing scheduled refresh for all vehicles'
    getChildDevices()?.each { child ->
        refreshVehicleData([vin: child.deviceNetworkId])
        pauseExecution(1000)
    }
}

private Map apiHeaders(boolean includeAuth = true) {
    def headers = [
        'User-Agent': 'vca-android/123',
        'vcc-api-key': settings.vccApiKey
    ]
    if (includeAuth && atomicState.accessToken) {
        headers['Authorization'] = "Bearer ${atomicState.accessToken}"
    }
    return headers
}

// Token endpoint calls carry no secret: mo2qy9h_10 proves possession with the
// PKCE verifier on code exchange, and with the refresh token afterwards.
private Map tokenHeaders() {
    return ['User-Agent': 'vca-android/123', 'Accept': 'application/json']
}

private String formatExpiry(Long expiryTimestamp) {
    if (!expiryTimestamp) { return 'N/A' }
    try {
        def df = new java.text.SimpleDateFormat('yyyy-MM-dd HH:mm:ss z')
        df.setTimeZone(location.timeZone ?: TimeZone.getDefault())
        return df.format(new Date(expiryTimestamp))
    } catch (e) {
        return new Date(expiryTimestamp).toString()
    }
}

private void logInfo(String msg) { log.info "${appName()}: ${msg}" }
private void logDebug(String msg) { if (settings.logEnable) { log.debug "${appName() }: ${msg }" } }
private void logWarn(String msg) { log.warn "${appName()}: ${msg}" }
private void logError(String msg) { log.error "${appName()}: ${msg}" }
