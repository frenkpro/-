#!/usr/bin/env python3
"""
Проверка VPN-конфигов (ss://, vmess://, trojan://, hysteria2://, hy2://, vless://).

Скрипт берёт список конфигов (по ссылке, из файла или одну строку),
достаёт из каждой строки адрес сервера, при необходимости узнаёт IP по
доменному имени, затем:
  * через whois узнаёт, кому принадлежит IP (организация, страна, сеть);
  * через бесплатный сервис Shodan InternetDB проверяет, есть ли IP в Shodan
    (открытые порты, уязвимости, имена хостов).

Использование:
  python3 ss_check.py "https://gitlab.com/.../BLACK_SS+All_RUS.txt?ref_type=heads"
  python3 ss_check.py list.txt
  python3 ss_check.py "ss://...."

Нужен только Python 3.8+, сторонние библиотеки не требуются.
"""

import base64
import json
import re
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request

SCHEMES = ("ss", "vmess", "trojan", "hysteria2", "hy2", "vless")
TIMEOUT = 15
USER_AGENT = "Mozilla/5.0 (ss_check.py)"


# ---------------------------------------------------------------------------
# Загрузка входных данных
# ---------------------------------------------------------------------------

def http_get(url):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return resp.read().decode("utf-8", errors="replace")


def load_lines(source):
    """Возвращает строки с конфигами из ссылки, файла или самой строки."""
    if source.startswith(("http://", "https://")):
        text = http_get(source)
    elif source.split("://", 1)[0].lower() in SCHEMES:
        text = source
    else:
        with open(source, encoding="utf-8", errors="replace") as f:
            text = f.read()

    lines = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.split("://", 1)[0].lower() in SCHEMES:
            lines.append(line)
    return lines


# ---------------------------------------------------------------------------
# Разбор конфигов
# ---------------------------------------------------------------------------

def b64decode(data):
    """base64 в обоих вариантах (обычный и для URL), с дополнением '='."""
    data = data.strip()
    data += "=" * (-len(data) % 4)
    try:
        return base64.urlsafe_b64decode(data).decode("utf-8", errors="replace")
    except Exception:
        return base64.b64decode(data).decode("utf-8", errors="replace")


def split_host_port(hostport):
    """'1.2.3.4:443' / '[::1]:443' / 'example.com:443' -> (host, port)."""
    m = re.match(r"^\[([^\]]+)\](?::(\d+))?$", hostport)
    if m:
        return m.group(1), m.group(2) or ""
    if hostport.count(":") == 1:
        host, port = hostport.split(":")
        return host, port
    return hostport, ""


def parse_query(query):
    return {k: v for k, v in urllib.parse.parse_qsl(query, keep_blank_values=True)}


def parse_ss(line):
    body = line[len("ss://"):]
    body, _, name = body.partition("#")
    body, _, query = body.partition("?")
    body = body.rstrip("/")

    if "@" in body:
        # Формат SIP002: ss://base64(method:password)@host:port
        userinfo, hostport = body.rsplit("@", 1)
        userinfo = urllib.parse.unquote(userinfo)
        if ":" not in userinfo:
            userinfo = b64decode(userinfo)
    else:
        # Старый формат: ss://base64(method:password@host:port)
        decoded = b64decode(body)
        userinfo, hostport = decoded.rsplit("@", 1)

    method, _, password = userinfo.partition(":")
    host, port = split_host_port(hostport)
    keys = {"method": method, "password": password}
    keys.update(parse_query(query))
    return host, port, keys, urllib.parse.unquote(name)


def parse_vmess(line):
    data = json.loads(b64decode(line[len("vmess://"):]))
    host = str(data.get("add", ""))
    port = str(data.get("port", ""))
    name = str(data.get("ps", ""))
    keys = {k: v for k, v in data.items() if k not in ("add", "port", "ps") and v not in ("", None)}
    return host, port, keys, name


def parse_generic(line):
    """trojan://, hysteria2://, hy2://, vless:// — вид scheme://secret@host:port?params#name"""
    body = line.split("://", 1)[1]
    body, _, name = body.partition("#")
    body, _, query = body.partition("?")
    body = body.rstrip("/")
    secret, _, hostport = body.rpartition("@")
    host, port = split_host_port(hostport)
    keys = {}
    if secret:
        keys["password/uuid"] = urllib.parse.unquote(secret)
    keys.update(parse_query(query))
    return host, port, keys, urllib.parse.unquote(name)


def parse_line(line):
    scheme = line.split("://", 1)[0].lower()
    if scheme == "ss":
        host, port, keys, name = parse_ss(line)
    elif scheme == "vmess":
        host, port, keys, name = parse_vmess(line)
    else:
        host, port, keys, name = parse_generic(line)
    return {"scheme": scheme, "host": host, "port": port, "keys": keys, "name": name}


# ---------------------------------------------------------------------------
# IP по доменному имени
# ---------------------------------------------------------------------------

def is_ip(host):
    for family in (socket.AF_INET, socket.AF_INET6):
        try:
            socket.inet_pton(family, host)
            return True
        except OSError:
            pass
    return False


def resolve(host):
    if is_ip(host):
        return [host]
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        return []
    ips = []
    for info in infos:
        ip = info[4][0]
        if ip not in ips:
            ips.append(ip)
    return ips


# ---------------------------------------------------------------------------
# Whois
# ---------------------------------------------------------------------------

def whois_query(server, query):
    with socket.create_connection((server, 43), timeout=TIMEOUT) as s:
        s.sendall((query + "\r\n").encode())
        chunks = []
        while True:
            data = s.recv(4096)
            if not data:
                break
            chunks.append(data)
    return b"".join(chunks).decode("utf-8", errors="replace")


def whois_raw(ip):
    """Спрашивает whois.iana.org, к какому регистратору относится IP, и идёт к нему."""
    text = whois_query("whois.iana.org", ip)
    m = re.search(r"^refer:\s*(\S+)", text, re.M | re.I)
    if not m:
        return text
    server = m.group(1)
    query = "n + " + ip if server == "whois.arin.net" else ip
    text = whois_query(server, query)

    # ARIN иногда отправляет к другому регистратору (например, для сетей RIPE/APNIC)
    m = re.search(r"^ReferralServer:\s*(?:whois|rwhois)://([^:/\s]+)", text, re.M | re.I)
    if m and m.group(1) != server:
        try:
            text = whois_query(m.group(1), ip)
        except OSError:
            pass
    return text


WHOIS_FIELDS = [
    ("Организация", ["OrgName", "org-name", "Organization", "owner", "descr"]),
    ("Сеть", ["NetName", "netname"]),
    ("Диапазон", ["CIDR", "NetRange", "inetnum", "inet6num"]),
    ("Страна", ["Country", "country"]),
    ("Abuse e-mail", ["OrgAbuseEmail", "abuse-mailbox"]),
]


def whois_info(ip):
    raw = whois_raw(ip)
    info = {}
    for label, keys in WHOIS_FIELDS:
        for key in keys:
            m = re.search(r"^" + re.escape(key) + r":\s*(.+)$", raw, re.M | re.I)
            if m:
                info[label] = m.group(1).strip()
                break
    return info


# ---------------------------------------------------------------------------
# Shodan InternetDB (бесплатно, без ключа)
# ---------------------------------------------------------------------------

def shodan_info(ip):
    """None — IP в Shodan нет; иначе словарь с данными."""
    try:
        return json.loads(http_get("https://internetdb.shodan.io/" + ip))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            return None
        raise


# ---------------------------------------------------------------------------
# Вывод
# ---------------------------------------------------------------------------

def check_ip(ip, cache):
    if ip in cache:
        return cache[ip]
    result = {}
    try:
        result["whois"] = whois_info(ip)
    except Exception as e:
        result["whois_error"] = str(e)
    try:
        result["shodan"] = shodan_info(ip)
    except Exception as e:
        result["shodan_error"] = str(e)
    cache[ip] = result
    return result


def print_ip_report(ip, result):
    print(f"  IP: {ip}")
    print("    Владелец (whois):")
    if "whois_error" in result:
        print(f"      ошибка запроса: {result['whois_error']}")
    elif not result["whois"]:
        print("      данных не найдено")
    else:
        for label, value in result["whois"].items():
            print(f"      {label}: {value}")

    print("    Shodan:")
    if "shodan_error" in result:
        print(f"      ошибка запроса: {result['shodan_error']}")
    elif result["shodan"] is None:
        print("      НЕТ в Shodan")
    else:
        s = result["shodan"]
        print("      ЕСТЬ в Shodan")
        print(f"      Открытые порты: {', '.join(map(str, s.get('ports', []))) or '-'}")
        print(f"      Имена хостов: {', '.join(s.get('hostnames', [])) or '-'}")
        print(f"      Метки: {', '.join(s.get('tags', [])) or '-'}")
        print(f"      Уязвимости: {', '.join(s.get('vulns', [])) or '-'}")
        print(f"      Подробнее: https://www.shodan.io/host/{ip}")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    try:
        lines = load_lines(sys.argv[1])
    except Exception as e:
        print(f"Не удалось получить список: {e}")
        sys.exit(1)

    print(f"Найдено конфигов: {len(lines)}\n")
    cache = {}
    sep = "=" * 80

    for n, line in enumerate(lines, 1):
        print(sep)
        print(f"[{n}/{len(lines)}] {line}")
        try:
            cfg = parse_line(line)
        except Exception as e:
            print(f"  Не удалось разобрать строку: {e}\n")
            continue

        print(f"  Протокол: {cfg['scheme']}")
        print(f"  Название: {cfg['name']}")
        print(f"  Сервер: {cfg['host']}  Порт: {cfg['port']}")
        print("  Ключи подключения:")
        for k, v in cfg["keys"].items():
            print(f"    {k}: {v}")

        ips = resolve(cfg["host"])
        if not ips:
            print(f"  Не удалось узнать IP для {cfg['host']}\n")
            continue
        if not is_ip(cfg["host"]):
            print(f"  {cfg['host']} -> {', '.join(ips)}")
        for ip in ips:
            print_ip_report(ip, check_ip(ip, cache))
        print()


if __name__ == "__main__":
    main()
