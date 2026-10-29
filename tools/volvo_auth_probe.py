#!/usr/bin/env python3
"""
Volvo ID login probe - reference implementation of the post-2026-10-26 flow.

Volvo disabled the OAuth client `h4Yf0b` on 2026-10-26. The Volvo Cars app now
uses public client `mo2qy9h_10` with Authorization Code + PKCE (S256); the
login itself is served by Keycloak at auth.volvocars.com/realms/volvocars and
the authorization code is returned to the app via a `vca://` redirect.

This script walks that flow end to end so the Hubitat app can be ported against
real request/response shapes. It is a debugging tool, not part of the package.

    python3 tools/volvo_auth_probe.py                  # full login -> tokens
    python3 tools/volvo_auth_probe.py --refresh-only    # test stored refresh token
    python3 tools/volvo_auth_probe.py --api-only --path /energy/v2/vehicles/<VIN>/state
    python3 tools/volvo_auth_probe.py --dpop            # also send DPoP proofs

Credentials are read from the terminal (getpass) and never written to disk.
Tokens go to tools/volvo_tokens.json (gitignored). The full hop chain goes to
tools/volvo_flow_chain.json (gitignored) - that file is what to read when
adjusting the Groovy port.

Requires Python 3.9+ standard library only. DPoP mode needs no extra deps
(uses hashlib/hmac-free ES256 only if `cryptography` is installed).
"""

from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import json
import os
import re
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request

AUTH_URL = "https://volvoid.eu.volvocars.com/as/authorization.oauth2"
TOKEN_URL = "https://volvoid.eu.volvocars.com/as/token.oauth2"
API_BASE = "https://api.volvocars.com"

CLIENT_ID = "mo2qy9h_10"
REDIRECT_URI = "vca://www.volvocars.com/l/vca/oauth/callback"

# Mirrors the scope set the current Volvo Cars app requests. Note that the old
# app code claimed location:read / energy:target_battery_level cause 400s - that
# was with the retired h4Yf0b client.
SCOPES = [
    "openid",
    "conve:brake_status",
    "conve:climatization_start_stop",
    "conve:command_accessibility",
    "conve:commands",
    "conve:diagnostics_engine_status",
    "conve:diagnostics_workshop",
    "conve:doors_status",
    "conve:engine_status",
    "conve:fuel_status",
    "conve:honk_flash",
    "conve:lock",
    "conve:lock_status",
    "conve:navigation",
    "conve:odometer_status",
    "conve:trip_statistics",
    "conve:tyre_status",
    "conve:unlock",
    "conve:vehicle_relation",
    "conve:warnings",
    "conve:windows_status",
    "energy:battery_charge_level",
    "energy:charging_connection_status",
    "energy:charging_system_status",
    "energy:electric_range",
    "energy:estimated_charging_time",
    "energy:recharge_status",
    "energy:state:read",
    "location:read",
]

USER_AGENT = "vca-android/123"
HERE = os.path.dirname(os.path.abspath(__file__))
TOKEN_FILE = os.path.join(HERE, "volvo_tokens.json")
CHAIN_FILE = os.path.join(HERE, "volvo_flow_chain.json")

VERBOSE = True
chain: list[dict] = []


def log(msg: str) -> None:
    print(msg, flush=True)


def b64url(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).decode().rstrip("=")


def pkce_pair() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(48)
    challenge = b64url(hashlib.sha256(verifier.encode()).digest())
    return verifier, challenge


class Jar:
    """RFC-6265-ish cookie jar: honours Domain/Path/host-only and expiry.

    Volvo's flow spans two hosts (volvoid.eu.volvocars.com and auth.volvocars.com)
    and each sets its own cookies, so host-only matching has to be exact - the
    Keycloak session cookies are Path=/realms/volvocars/ and host-only.
    """

    def __init__(self) -> None:
        self._cookies: list[dict] = []

    def store(self, url: str, headers: urllib.request.HTTPMessage) -> None:
        req_host = urllib.parse.urlsplit(url).netloc.split(":")[0].lower()
        req_path = urllib.parse.urlsplit(url).path or "/"
        bucket = {}
        for raw in headers.get_all("Set-Cookie") or []:
            parts = raw.split(";")
            if "=" not in parts[0]:
                continue
            name, value = parts[0].split("=", 1)
            name = name.strip()
            attrs = {}
            for p in parts[1:]:
                p = p.strip()
                if "=" in p:
                    k, v = p.split("=", 1)
                    attrs[k.lower()] = v.strip()
                else:
                    attrs[p.lower()] = True
            domain = attrs.get("domain", "").lstrip(".").lower()
            host_only = not domain
            if not domain:
                domain = req_host
            path = attrs.get("path", "/") or "/"
            existing = next((c for c in self._cookies
                             if c["name"] == name and c["domain"] == domain and c["path"] == path), None)
            if value == "" or (attrs.get("max-age") not in (None,) and str(attrs.get("max-age")) == "0"):
                self._cookies = [c for c in self._cookies if c is not existing]
                continue
            rec = {"name": name, "value": value, "domain": domain, "path": path,
                   "host_only": host_only, "secure": "secure" in attrs}
            if existing:
                existing.update(rec)
            else:
                self._cookies.append(rec)
            bucket[name] = path
        if bucket:
            log(f"    cookies[{req_host}]: " + ", ".join(f"{n}@{p}" for n, p in bucket.items()))

    @staticmethod
    def _path_matches(req_path: str, cookie_path: str) -> bool:
        if req_path == cookie_path:
            return True
        if not req_path.startswith(cookie_path):
            return False
        return cookie_path.endswith("/") or req_path[len(cookie_path)] == "/"

    def header(self, url: str) -> str:
        split = urllib.parse.urlsplit(url)
        host = split.netloc.split(":")[0].lower()
        path = split.path or "/"
        out, seen = [], set()
        for c in self._cookies:
            if c["host_only"]:
                if c["domain"] != host:
                    continue
            elif not (host == c["domain"] or host.endswith("." + c["domain"])):
                continue
            if c["secure"] and split.scheme != "https":
                continue
            if not self._path_matches(path, c["path"]):
                continue
            if c["name"] in seen:
                continue
            seen.add(c["name"])
            out.append(f"{c['name']}={c['value']}")
        return "; ".join(out)

    def hosts(self) -> dict:
        """host -> cookie names, for logging/diagnostics only."""
        out: dict[str, list[str]] = {}
        for c in self._cookies:
            out.setdefault(c["domain"], []).append(c["name"])
        return {k: sorted(v) for k, v in out.items()}


def kc_string(html: str, key: str) -> str | None:
    m = re.search('"' + key + r'":\s*"((?:[^"\\]|\\.)*)"', html)
    if not m:
        return None
    try:
        return json.loads('"' + m.group(1) + '"')
    except Exception:
        return m.group(1)


def parse_login_page(html: str) -> dict:
    """Read pageId / loginAction / error summary out of a Keycloak kcContext."""
    return {
        "pageId": kc_string(html, "pageId"),
        "action": kc_string(html, "loginAction"),
        "message": kc_string(html, "summary"),
        "registerAction": kc_string(html, "registrationAction"),
    }


def scrub(text: str) -> str:
    """Redact secrets before anything is logged or written to the chain file."""
    text = re.sub(r'("?(?:password|code|refresh_token|access_token|id_token)"?\s*[:=]\s*"?)([A-Za-z0-9\-._~]{6,})',
                  r"\1<redacted>", text)
    return text


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Never follow redirects - the flow must see every 302 to keep per-hop cookies."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: N802
        return None


_OPENER = urllib.request.build_opener(NoRedirect)


def http_request(url: str, jar: Jar, *, method: str = "GET",
                 form: dict | None = None, headers: dict | None = None,
                 dpop: str | None = None, record: str = "") -> tuple[int, str, dict]:
    """Single hop, no automatic redirect following."""
    if url.startswith("http://"):
        url = "https://" + url[len("http://"):]  # Volvo hands out http:// links

    host = urllib.parse.urlsplit(url).netloc
    req_headers = {"User-Agent": USER_AGENT, "Accept": "text/html,application/json;q=0.9,*/*;q=0.8"}
    cookie = jar.header(url)
    if cookie:
        req_headers["Cookie"] = cookie
    if form is not None:
        req_headers["Content-Type"] = "application/x-www-form-urlencoded"
    if dpop:
        req_headers["DPoP"] = dpop
    req_headers.update(headers or {})

    body = urllib.parse.urlencode(form).encode() if form is not None else None
    req = urllib.request.Request(url, data=body, method=method, headers=req_headers)

    entry = {"hop": len(chain) + 1, "record": record, "method": method,
             "url": scrub(url), "form": {k: ("<redacted>" if k in ("password", "otp") else v)
                                         for k, v in (form or {}).items()}}
    try:
        resp = _OPENER.open(req, timeout=30)
        status, resp_headers, raw = resp.status, resp.headers, resp.read()
    except urllib.error.HTTPError as exc:
        status, resp_headers, raw = exc.code, exc.headers, exc.read()

    text = raw.decode("utf-8", "replace")
    jar.store(url, resp_headers)
    loc = resp_headers.get("Location")

    entry["status"] = status
    entry["location"] = scrub(loc) if loc else None
    entry["jar_after"] = jar.hosts()
    entry["content_type"] = resp_headers.get("Content-Type")
    entry["body"] = scrub(text[:4000])
    chain.append(entry)

    if VERBOSE:
        log(f"  -> {method} {scrub(url.split('?')[0])}  =>  HTTP {status}")
        if loc:
            log(f"     Location: {scrub(loc)[:160]}")
        log(f"     body: {len(text)} bytes, ctype={entry['content_type']}")
    return status, text, dict(resp_headers)


def follow(jar: Jar, url: str, *, method: str = "GET", form: dict | None = None,
           dpop: str | None = None, record: str = "") -> dict:
    """Follow redirects by hand. Stops at a page, or at the vca:// callback."""
    for _ in range(12):
        status, text, headers = http_request(url, jar, method=method, form=form, dpop=dpop, record=record)
        if 300 <= status < 400:
            loc = headers.get("Location")
            if not loc:
                return {"error": f"{status} with no Location header", "html": text}
            if loc.startswith(REDIRECT_URI.split("?")[0]) or loc.startswith("vca://"):
                params = urllib.parse.parse_qs(urllib.parse.urlsplit(loc).query)
                return {"code": (params.get("code") or [None])[0],
                        "state": (params.get("state") or [None])[0],
                        "error": (params.get("error_description") or params.get("error") or [None])[0],
                        "raw_location": loc}
            url = urllib.parse.urljoin(url, loc)
            method, form, dpop, record = "GET", None, None, record + "/redirect"
            continue
        if status >= 400:
            return {"error": f"HTTP {status}", "html": text}
        return {"html": text}
    return {"error": "too many redirects"}


def token_request(form: dict, dpop: str | None, label: str) -> dict:
    status, text, _ = http_request(TOKEN_URL, Jar(), method="POST", form=form, dpop=dpop, record=label)
    try:
        data = json.loads(text)
    except Exception:
        data = {"raw": text[:500]}
    if status != 200:
        log(f"  !! token request failed: HTTP {status} {scrub(text[:400])}")
        return {}
    log(f"  ++ token ok: scope={len((data.get('scope') or '').split())} grants, "
        f"expires_in={data.get('expires_in')}, refresh_token={'yes' if data.get('refresh_token') else 'no'}")
    return data


def call_api(path: str, access_token: str, vcc_key: str) -> int:
    """Prove the token is accepted by the same endpoints the Hubitat app uses."""
    if not vcc_key:
        log(f"  (skipped {path}: no --vcc-key given)")
        return 0
    jar = Jar()
    log(f"\n=== API check: GET {API_BASE}{path}")
    status, text, _ = http_request(API_BASE + path, jar, headers={
        "Authorization": f"Bearer {access_token}", "vcc-api-key": vcc_key,
        "Accept": "application/json"}, record=f"api:{path}")
    log(f"  HTTP {status}: {scrub(text[:400])}")
    return status


def check_paths(paths: list, access_token: str, vcc_key: str) -> None:
    rejected = [p for p in paths if call_api(p, access_token, vcc_key) >= 400]
    if rejected:
        log(f"  !! {len(rejected)} endpoint(s) rejected the token: {', '.join(rejected)}")
        log("     A 403 there means a needed scope is missing from SCOPES.")


def do_login(vcc_key: str, *, use_dpop: bool, probe_only: bool = False, paths: list | None = None) -> dict:
    verifier, challenge = pkce_pair()
    dpop = make_dpop(TOKEN_URL, "POST") if use_dpop else None

    log("\n=== Step 1: GET authorize (PKCE S256, redirect to vca://)")
    params = {
        "client_id": CLIENT_ID,
        "redirect_uri": REDIRECT_URI,
        "response_type": "code",
        "scope": " ".join(SCOPES),
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "acr_values": "urn:volvoid:aal:bronze:2sv",
        "state": secrets.token_urlsafe(12),
    }
    jar = Jar()
    page = follow(jar, AUTH_URL + "?" + urllib.parse.urlencode(params), record="authorize")
    if "error" in page and "html" not in page:
        log(f"  !! authorize failed: {page['error']}")
        return {}

    login = parse_login_page(page["html"])
    log(f"  kcContext pageId={login['pageId']!r} action={scrub(str(login['action']))[:110]!r}")
    if login["pageId"] != "login" or not login["action"]:
        log("  !! expected Keycloak pageId=login. Volvo served something else - "
            "check volvo_flow_chain.json")
        return {}
    if probe_only:
        log("  ++ login page reachable; stopping before credentials (--probe-only)")
        return {"probe": True}

    user = input("Volvo ID email: ").strip()
    password = getpass.getpass("Volvo ID password (not echoed, not stored): ")

    log("\n=== Step 2: POST credentials to loginAction")
    result = follow(jar, login["action"], method="POST",
                    form={"username": user, "password": password, "credentialId": ""},
                    record="credentials")
    otp_page = parse_login_page(result.get("html", ""))
    log(f"  kcContext pageId={otp_page['pageId']!r} message={otp_page['message']!r}")
    if otp_page["pageId"] != "login-otp-email" or not otp_page["action"]:
        log("  !! Volvo did not present the email-OTP form. "
            f"error={result.get('error')!r} pageId={otp_page['pageId']!r}")
        log("     If the message mentions a wrong password, re-run. Otherwise inspect the chain file.")
        return {}
    log("  ++ OTP emailed to the address on the Volvo ID. Check your inbox.")

    for attempt in range(3):
        otp = input(f"OTP code (attempt {attempt + 1}/3): ").strip()
        log("\n=== Step 3: POST otp -> expect redirect to vca://")
        result = follow(jar, otp_page["action"], method="POST", form={"otp": otp},
                        record=f"otp{attempt}")
        if result.get("code"):
            log(f"  ++ authorization code received (len={len(result['code'])})")
            log(f"     final redirect: {result['raw_location'].split('?')[0]}")
            log("\n=== Step 4: POST token exchange (code + code_verifier"
                f"{', +DPoP' if use_dpop else ', no DPoP'})")
            tokens = token_request({
                "grant_type": "authorization_code",
                "code": result["code"],
                "redirect_uri": REDIRECT_URI,
                "client_id": CLIENT_ID,
                "code_verifier": verifier,
            }, dpop, "exchange")
            if tokens:
                finish(tokens, vcc_key, dpop, paths or [])
            return tokens
        otp_page = parse_login_page(result.get("html", ""))
        if otp_page["pageId"] == "login-otp-email" and otp_page["action"]:
            log(f"  -- OTP rejected: {otp_page['message']!r} - form re-served with a new action")
            continue
        log(f"  !! login session dead: {result.get('error')!r} pageId={otp_page['pageId']!r}")
        return {}
    return {}


def finish(tokens: dict, vcc_key: str, dpop: str | None, paths: list) -> None:
    with open(TOKEN_FILE, "w") as fh:
        json.dump(tokens, fh, indent=1)
    os.chmod(TOKEN_FILE, 0o600)
    log(f"  tokens written to {TOKEN_FILE} (mode 600)")

    call_api("/connected-vehicle/v2/vehicles", tokens["access_token"], vcc_key)
    if paths:
        check_paths(paths, tokens["access_token"], vcc_key)

    if tokens.get("refresh_token"):
        log("\n=== Step 5: refresh_token grant (does a non-DPoP refresh work?)")
        refreshed = token_request({
            "grant_type": "refresh_token",
            "client_id": CLIENT_ID,
            "refresh_token": tokens["refresh_token"],
        }, dpop, "refresh")
        if refreshed:
            call_api("/connected-vehicle/v2/vehicles", refreshed["access_token"], vcc_key)
            if paths:
                check_paths(paths, refreshed["access_token"], vcc_key)


def make_dpop(htu: str, htm: str) -> str | None:
    """ES256 DPoP proof. Returns None if `cryptography` is unavailable."""
    try:
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature
        from cryptography.hazmat.primitives.asymmetric.ec import EllipticCurvePrivateKey
    except ImportError:
        log("  (DPoP requested but python `cryptography` is not installed - sending no proof)")
        return None
    key: EllipticCurvePrivateKey = ec.generate_private_key(ec.SECP256R1())
    pub = key.public_key().public_numbers()
    header = {"typ": "dpop+jwt", "alg": "ES256",
              "jwk": {"kty": "EC", "crv": "P-256",
                      "x": b64url(pub.x.to_bytes(32, "big")), "y": b64url(pub.y.to_bytes(32, "big"))}}
    payload = {"htm": htm, "htu": htu, "iat": int(__import__("time").time()),
               "jti": secrets.token_hex(16)}
    signing_input = (b64url(json.dumps(header).encode()) + "." + b64url(json.dumps(payload).encode())).encode()
    der = key.sign(signing_input, ec.ECDSA(hashes.SHA256()))
    r, s = decode_dss_signature(der)
    sig = r.to_bytes(32, "big") + s.to_bytes(32, "big")
    return signing_input.decode() + "." + b64url(sig)


def main() -> int:
    global VERBOSE
    ap = argparse.ArgumentParser(description="Probe the post-2026-10-26 Volvo ID flow")
    ap.add_argument("--refresh-only", action="store_true", help="only test the stored refresh token")
    ap.add_argument("--api-only", action="store_true", help="only call the vehicle API with the stored token")
    ap.add_argument("--dpop", action="store_true", help="send DPoP proofs (proves the app-exact path)")
    ap.add_argument("--probe-only", action="store_true", help="stop after loading the login page (no credentials)")
    ap.add_argument("--quiet", action="store_true", help="less per-hop logging")
    ap.add_argument("--vcc-key", default=os.environ.get("VCC_API_KEY", ""), help="VCC API key for the API check")
    ap.add_argument("--path", action="append", default=[],
                    help="API path to check after tokens are obtained (repeatable), e.g. "
                         "/energy/v2/vehicles/<VIN>/state or /location/v1/vehicles/<VIN>/location")
    args = ap.parse_args()
    VERBOSE = not args.quiet

    if args.refresh_only or args.api_only:
        if not os.path.exists(TOKEN_FILE):
            log(f"No {TOKEN_FILE} - run a full login first.")
            return 1
        tokens = json.load(open(TOKEN_FILE))
        if args.api_only:
            check_paths(args.path or ["/connected-vehicle/v2/vehicles"], tokens["access_token"], args.vcc_key)
            return 0
        refreshed = token_request({
            "grant_type": "refresh_token", "client_id": CLIENT_ID,
            "refresh_token": tokens["refresh_token"]},
            make_dpop(TOKEN_URL, "POST") if args.dpop else None, "refresh-only")
        if refreshed:
            with open(TOKEN_FILE, "w") as fh:
                json.dump(refreshed, fh, indent=1)
            os.chmod(TOKEN_FILE, 0o600)
            check_paths(args.path or ["/connected-vehicle/v2/vehicles"], refreshed["access_token"], args.vcc_key)
            return 0
        return 1

    try:
        tokens = do_login(args.vcc_key, use_dpop=args.dpop, probe_only=args.probe_only, paths=args.path)
    finally:
        with open(CHAIN_FILE, "w") as fh:
            json.dump(chain, fh, indent=1)
        log(f"\nHop chain written to {CHAIN_FILE} ({len(chain)} hops)")
    return 0 if tokens else 1


if __name__ == "__main__":
    sys.exit(main())
