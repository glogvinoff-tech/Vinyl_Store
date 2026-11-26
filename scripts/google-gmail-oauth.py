import getpass
import hashlib
import json
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


SCOPE = "https://www.googleapis.com/auth/gmail.send"
AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN_URL = "https://oauth2.googleapis.com/token"
ENV_PATH = Path(__file__).resolve().parents[1] / ".env"


def b64url(value):
    import base64

    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def write_env(values):
    lines = ENV_PATH.read_text(encoding="utf-8").splitlines() if ENV_PATH.exists() else []
    pending = dict(values)
    updated = []
    for line in lines:
        name = line.split("=", 1)[0] if "=" in line else ""
        if name in pending:
            updated.append(f"{name}={pending.pop(name)}")
        else:
            updated.append(line)
    updated.extend(f"{name}={value}" for name, value in pending.items())
    ENV_PATH.write_text("\n".join(updated) + "\n", encoding="utf-8")


def main():
    if not ENV_PATH.exists():
        raise SystemExit(".env не найден. Сначала создайте его из .env.example.")

    client_id = input("Google OAuth Client ID: ").strip()
    client_secret = getpass.getpass("Google OAuth Client Secret: ").strip()
    if not client_id or not client_secret:
        raise SystemExit("Client ID и Client Secret обязательны.")

    verifier = secrets.token_urlsafe(64)
    challenge = b64url(hashlib.sha256(verifier.encode("ascii")).digest())
    state = secrets.token_urlsafe(32)
    result = {}

    class CallbackHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if self.path.split("?", 1)[0] != "/oauth2callback" or query.get("state", [None])[0] != state:
                self.send_error(400)
                return
            result["error"] = query.get("error", [None])[0]
            result["code"] = query.get("code", [None])[0]
            body = b"<html><body><h2>Google authorization received. You can close this tab.</h2></body></html>"
            self.send_response(200 if result.get("code") else 400)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format_string, *args):
            return

    server = HTTPServer(("127.0.0.1", 0), CallbackHandler)
    server.timeout = 1
    redirect_uri = f"http://127.0.0.1:{server.server_port}/oauth2callback"
    params = {
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "response_type": "code",
        "scope": SCOPE,
        "access_type": "offline",
        "prompt": "consent",
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    }
    authorization_url = f"{AUTH_URL}?{urllib.parse.urlencode(params)}"
    print("Открываю страницу Google для подтверждения доступа к отправке писем...")
    webbrowser.open(authorization_url)
    deadline = time.monotonic() + 300
    try:
        while not result and time.monotonic() < deadline:
            server.handle_request()
    finally:
        server.server_close()

    if not result:
        raise SystemExit("Не получен ответ Google за 5 минут.")
    if result.get("error") or not result.get("code"):
        raise SystemExit(f"Google authorization failed: {result.get('error', 'no authorization code')}")

    form = urllib.parse.urlencode({
        "client_id": client_id,
        "client_secret": client_secret,
        "code": result["code"],
        "code_verifier": verifier,
        "redirect_uri": redirect_uri,
        "grant_type": "authorization_code",
    }).encode("ascii")
    request = urllib.request.Request(TOKEN_URL, data=form, headers={
        "Content-Type": "application/x-www-form-urlencoded",
    })
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            tokens = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        try:
            details = json.loads(error.read().decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            details = {}
        reason = details.get("error", "unknown_error")
        description = details.get("error_description", "")
        raise SystemExit(f"Google отклонил OAuth-запрос ({error.code}): {reason}. {description}") from None
    except (urllib.error.URLError, json.JSONDecodeError) as error:
        raise SystemExit(f"Не удалось получить OAuth-токен Google: {error}") from None

    refresh_token = tokens.get("refresh_token")
    if not refresh_token:
        raise SystemExit("Google не выдал refresh token. Запустите скрипт повторно и подтвердите доступ.")

    write_env({
        "GOOGLE_OAUTH_CLIENT_ID": client_id,
        "GOOGLE_OAUTH_CLIENT_SECRET": client_secret,
        "GOOGLE_OAUTH_REFRESH_TOKEN": refresh_token,
    })
    print("OAuth-настройки сохранены в локальный .env. Значения токенов не выведены.")


if __name__ == "__main__":
    main()