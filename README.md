# Volvo Cars Integration for Hubitat

Connect your Hubitat Elevation® hub to your Volvo car(s) using the Volvo Cars API. Get vehicle status updates (lock status, fuel, battery, location, etc.) and send basic remote commands (lock, unlock, remote start/stop).

## 🚀 Evolution from hubitat-voc

This project represents the evolution of my previous work on Volvo integrations for Hubitat, specifically the hubitat-voc project (https://github.com/brianschmitt/hubitat-voc). While that earlier integration relied on unofficial methods to interact with Volvo services, this new integration is built to leverage the official Volvo Cars API available through the Developer Portal. Users currently using the hubitat-voc integration are strongly encouraged to migrate to this new version for a more official, maintainable, and potentially more stable connection to their vehicle. Please note that the setup process is different for this new integration as it requires obtaining an API key from the Volvo Developer Portal.

## ✨ Inspiration

This project is inspired by the excellent [thomasddn/ha-volvo-cars](https://github.com/thomasddn/ha-volvo-cars) integration for Home Assistant.

## Features

This integration installs as a Hubitat Parent App and creates Child Devices for each connected vehicle.

*   Manage multiple vehicles from the same Volvo ID account.
*   Poll vehicle status data:
    *   Door & Lock Status
    *   Window & Sunroof Status
    *   Fuel Level & Distance to Empty Tank
    *   Battery Charge Level & Electric Range (for applicable vehicles)
    *   Charging Status & Connection Status (for applicable vehicles)
    *   Odometer
    *   Last Known Location (Latitude, Longitude, Heading)
    *   Tire Status/Warnings
    *   Brake Fluid Status
    *   Oil & Coolant Level Warnings
    *   Service Interval Information (Distance/Time/Engine Hours)
    *   Warning Lights Status
    *   Trip Statistics (Average Consumption, Average Speed, Trip Meters)
*   Send Remote Commands (requires command accessibility status to allow the action):
    *   Lock / Unlock Doors
    *   Remote Engine Start / Stop
    *   Remote Climatization Start / Stop
    *   Honk Horn
    *   Flash Lights
    *   Honk & Flash
    *   Lock with Reduced Guard (if supported)
*   Supports Imperial unit conversion option for distance and consumption.
*   Configurable data refresh interval.

## ⚠️ Important Notes & Limitations

*   **API Key Requirement:** You *must* obtain a VCC API Key from the [Volvo Cars Developer Portal](https://developer.volvocars.com/). You will need one API Key *per Volvo ID account*.
*   **Authentication Flow:** Sign-in uses your Volvo ID username and password, followed by an OTP sent to your email. It follows the same OAuth flow the Volvo Cars mobile app uses - see [Sign-In and Tokens](#sign-in-and-tokens).
*   **Volvo retired the old login on 2026-10-26:** versions 0.3.x and earlier fail with `400 Bad Request`, `Could not load the Volvo ID login page`, or `Refresh token missing`. Update the app code to 0.4.1 or later and sign in again.
*   **API Rate Limits:** The Volvo API has rate limits (typically 10,000 requests per day per API key). The integration polls multiple endpoints for each vehicle during each refresh cycle. Choose your "Vehicle Data Refresh Interval" wisely (e.g., 10-15 minutes or longer) to avoid hitting this limit, especially if you have multiple vehicles.
*   **Future Features:** [Enhancements](ENHANCEMENTS.md)
*   **Supported Regions:** For a complete list of supported countries, check out [Volvo's API documentation on supported locations](https://developer.volvocars.com/terms-and-conditions/apis-supported-locations/).

## Requirements

1.  A Hubitat Elevation® Hub (running firmware 2.2.8 or later, as specified in `packageManifest.json`).
2.  A Volvo ID account with one or more vehicles linked.
3.  A Volvo Cars Developer Account (free).
4.  A VCC API Key generated from your Volvo Cars Developer Account. You need *one key per Volvo ID account* you wish to connect.

## Installation

Installation is recommended via Hubitat Package Manager (HPM).

### Via Hubitat Package Manager (Recommended)

1.  Install [Hubitat Package Manager](https://github.com/HubitatCommunity/HubitatPackageManager).
2.  In HPM, select `Install a User App`.
3.  Click `Add User Repository`.
4.  Enter the URL for this repository in the "Repository URI" field:
    `https://raw.githubusercontent.com/brianschmitt/hubitat-volvo-cars/main/packageManifest.json`
5.  Click `Save`.
6.  Go back to the main HPM page and select `Install`.
7.  Find "Volvo Cars Integration" in the list, select it, and click `Next`.
8.  Confirm the components to install (the Parent App and the Child Driver) and click `Next`.
9.  Click `Install`.

### Manual Installation

1.  Go to your Hubitat hub's administrative interface. (You may also choose to use the import from url in the app/driver pages)
2.  Go to `Apps Code`.
3.  Click `New App`.
4.  Open the raw Groovy code for the app: `https://raw.githubusercontent.com/brianschmitt/hubitat-volvo-cars/main/app/volvo-cars-app.groovy`
5.  Copy the entire contents of the raw file.
6.  Paste the code into the Hubitat `New App` editor.
7.  Click `Save`.
8.  Go to `Drivers Code`.
9.  Click `New Driver`.
10. Open the raw Groovy code for the driver: `https://raw.githubusercontent.com/brianschmitt/hubitat-volvo-cars/main/drivers/volvo-cars-driver.groovy`
11. Copy the entire contents of the raw file.
12. Paste the code into the Hubitat `New Driver` editor.
13. Click `Save`.

## Setup and Configuration

1.  Go to your Hubitat hub's administrative interface.
2.  Go to `Apps` and click `Add User App`.
3.  Select "Volvo Cars" from the list. This opens the main configuration page.
4.  Click the "Manage Credentials & API Key" link.
5.  On the "Volvo Credentials & API Key" page:
    *   Enter your Volvo ID Username (Email) and Password.
    *   Enter your VCC API Key obtained from the [Volvo Developer Portal](https://developer.volvocars.com/account/#your-api-applications).
    *   Upon entering all required fields, the app will automatically attempt the first step of authentication.
6.  After successful submission, you will be directed to the "OTP Authentication" page.
7.  Check the email address associated with your Volvo ID. You should receive a 6-digit One Time Password (OTP).
8.  Enter the OTP code into the "OTP Code" field in the Hubitat app settings. Entering the code will trigger the next authentication step.
9.  If authentication is successful, you will be redirected to the "Manage Vehicles" page. The authentication status on the main page should show "Authenticated".
10. **Manage Vehicles:**
    *   On the "Manage Vehicles" page, the app will attempt to list the VINs associated with your authenticated Volvo ID.
    *   If VINs are found, select the desired VIN from the "Select Vehicle to add:" dropdown.
    *   Click the "Add Vehicle" button. (This process takes a few seconds to complete **be patient**)
    *   This will create a new child device under this app instance, representing your car. The device will be named "Volvo XXXX" where XXXX is the last 4 digits of the VIN. You can rename it on the device page later if desired.
    *   To remove a vehicle, click the "Remove [Vehicle Name] ([VIN])" link under "Existing Vehicles", then confirm on the next page.
11. **Configure Settings:**
    *   Go back to the main app page.
    *   Adjust the "Vehicle Data Refresh Interval" in the "Settings" section. Be mindful of the API rate limit (10,000 calls/day per key). **DEFAULT** Every 10 Minutes
    *   Enable "Enable debug logging" if you need detailed logs for troubleshooting. Logging automatically turns off after 30 minutes.
    *   On the individual child vehicle device page, you can also configure "Use Imperial Measurements" and "Remote Start Duration".

## Sign-In and Tokens

The app authenticates the way the Volvo Cars mobile app does, with no client secret:

1.  Authorize against `volvoid.eu.volvocars.com` using the mobile client id, a PKCE `S256` challenge, and the `vca://www.volvocars.com/l/vca/oauth/callback` redirect.
2.  Volvo redirects to its Keycloak login at `auth.volvocars.com`, where the app submits your Volvo ID and password.
3.  Keycloak emails a one-time code. Enter it in the app.
4.  The redirect chain ends in a `vca://` callback carrying an authorization code, which the app exchanges with the PKCE verifier at `as/token.oauth2`.

Access tokens are valid for **30 minutes**. The app refreshes at 80% of the token lifetime - about six minutes before a 30-minute token expires - and logs `Signed in to Volvo ID. Access token valid until …` once per interactive sign-in. **Revoke Authentication** clears the stored tokens and the refresh schedule.

Two Hubitat HTTP behaviours are load-bearing here, and the code handles both explicitly:

*   `followRedirects: false` - Hubitat must not follow the `volvoid` to `auth.volvocars.com` redirect itself, because the Keycloak session cookie is minted on that hop.
*   `getHeaders('Set-Cookie')` - reading `resp.headers['Set-Cookie']` keeps only the *last* cookie of a multi-cookie response, which is `KC_RESTART` and guarantees a login loop. Keycloak's `AUTH_SESSION_ID` comes first.

### If sign-in fails

Auth failures log one `WARN` line, `Auth failure detail: …`. Read it left to right:

| field | what it tells you |
| :--- | :--- |
| `status=` | the HTTP status Volvo returned |
| `location=` | where Volvo tried to send the browser; a location starting `vca://` means the flow reached the callback and the code is there (values are redacted in logs) |
| `sentCookies=` | cookies the app had for that request - `-` or a list without `AUTH_SESSION_ID` on the credentials POST means the session was lost |
| `setCookies=` | cookies Volvo issued on the previous response - `AUTH_SESSION_ID` must appear here |
| `body=` | a redacted snippet of the response, when Volvo sent one |

Symptoms and causes:

*   `Too many redirects while signing in to Volvo ID` - the session cookie is not reaching Keycloak, so it restarts the flow.
*   `Volvo did not send an OTP code` - the credentials POST was rejected. Usually a wrong password, or Volvo changed the login page shape.
*   `Volvo rejected the OTP code: …` - the code was wrong or expired. Press **Request New OTP**.
*   `Could not load the Volvo ID login page` - Volvo moved the login, or the client id is no longer accepted.

### If Volvo changes the login again

`tools/volvo_auth_probe.py` runs the same login in a terminal using only the Python standard library (Python 3.9+), and stores tokens in `tools/volvo_tokens.json` when it succeeds. Paste the refresh token into **Already Have A Token?** and press **Use Pasted Refresh Token** to keep vehicles updating while the in-app login is broken. It is cleared from settings as soon as it is used.

## Using the Vehicle Device

Once a child device is created for your vehicle, you can find it under `Devices`. Click on the device name (e.g., "Volvo 1234") to view its details page.

Here you will see:

*   **Current States:** This section displays all the attributes the driver exposes (fuel level, odometer, lock status, tire pressure, warnings, location, etc.) with their current values.
*   **Commands:** This section lists the commands you can send to the vehicle (e.g., `lock`, `unlock`, `refresh`, `startEngine`, `stopEngine`, `startClimatization`, `stopClimatization`, `siren` (honk), `strobe` (flash), `both` (honk & flash), `lockDoorsReducedGuard`).

You can use these attributes and commands in Hubitat apps like Rule Machine, Dashboard, and Hubitat Safety Monitor to automate actions or display information based on your vehicle's status.

## Getting Help and Support

If you encounter issues or have questions:

1.  Check the Hubitat logs (`Logs`) for any error or debug messages from "Volvo Cars" or your vehicle device.
2.  Visit the Hubitat Community Forum. Search for existing threads related to this integration or create a new post asking for assistance. *(**Note:** Link to your specific community thread here once it exists).*

## Contributing

Contributions are welcome! If you find a bug, have a feature request, or want to improve the code, please feel free to:

1.  Open an [Issue](https://github.com/brianschmitt/hubitat-volvo-cars/issues) on GitHub
2.  Submit a [Pull Request](https://github.com/brianschmitt/hubitat-volvo-cars/pulls) on GitHub

## License

This project is licensed under the Apache-2.0 License - see the [LICENSE](LICENSE) file for details.