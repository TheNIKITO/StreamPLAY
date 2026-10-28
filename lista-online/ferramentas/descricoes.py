"""
Completa a lista do StreamPLAY com descrição, ano, gênero, nota e imagem de fundo, buscando no TMDB.

- Filmes: cada linha de filme ganha os dados que ainda não tiver.
- Séries: a primeira linha de cada série ganha os dados (o app usa para a série inteira).
- Nunca apaga o que já existe na lista: só acrescenta o que falta.
- Guarda o que já buscou em tmdb-cache.json: nas próximas vezes só procura os títulos novos.

Uso: python descricoes.py [caminho-da-lista]   (padrão: ..\\public\\lista.m3u)
A chave da API do TMDB fica em ..\\tmdb-chave.txt (o script pede na primeira vez).
"""
import json
import os
import re
import sys
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

PASTA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # lista-online
LISTA = sys.argv[1] if len(sys.argv) > 1 else os.path.join(PASTA, "public", "lista.m3u")
CHAVE = os.path.join(PASTA, "tmdb-chave.txt")
CACHE = os.path.join(PASTA, "tmdb-cache.json")
NAO_ACHADOS = os.path.join(PASTA, "nao-encontrados.txt")
BASE = os.environ.get("TMDB_BASE", "https://api.themoviedb.org/3")   # TMDB_BASE só para testes
IMG = "https://image.tmdb.org/t/p/w1280"
CAMPOS = ("descricao", "ano", "genero", "nota", "fundo")
# outros nomes que o app também aceita: se a linha já tiver um deles, o campo não é acrescentado
APELIDOS = {
    "descricao": ("tvg-plot", "plot", "descricao", "descrição", "description", "sinopse"),
    "ano": ("year", "ano", "tvg-year"),
    "genero": ("genre", "genero", "gênero", "tvg-genre"),
    "nota": ("rating", "nota", "tvg-rating"),
    "fundo": ("backdrop", "fundo", "tvg-backdrop", "banner", "background"),
}

ATTR = re.compile(r'([\w-]+)\s*=\s*"([^"]*)"')
# "Nome S01E02", "Nome - S1 E2", "Nome T01E02", "Nome 1x02" (mesma ideia do app)
EP = re.compile(r'^(?P<n>.*?)[\s._\-–|:]*(?:[ST]\d{1,2}\s*[._\-]?\s*E[Pp]?\s*\d{1,4}|\d{1,2}x\d{1,4})(?![0-9])', re.I)
EP2 = re.compile(r'^(?P<n>.*?)[\s._\-–|:]*Temporada\s*\d{1,2}', re.I)
ANO = re.compile(r'[\(\[]\s*((?:19|20)\d{2})\s*[\)\]]')
LIXO = re.compile(r'[\(\[][^\)\]]*[\)\]]|\b(dublado|legendado|dub|leg|nacional|4k|uhd|fhd|hd|sd|1080p|720p|cam|ts)\b', re.I)


def norm(s):
    s = unicodedata.normalize("NFD", s or "")
    return re.sub(r"\s+", " ", "".join(c for c in s if unicodedata.category(c) != "Mn")).strip().lower()


def tipo(grupo, url):
    g = norm(grupo).lstrip()
    if re.match(r"(filmes?|movies?|vod)(?![a-z])", g) or "/movie/" in (url or ""):
        return "filme"
    if re.match(r"(series?|seriados?)(?![a-z])", g) or "/series/" in (url or ""):
        return "serie"
    return None


def titulo_limpo(nome, kind):
    ano = None
    m = ANO.search(nome)
    if m:
        ano = m.group(1)
    if kind == "serie":
        m = EP.match(nome) or EP2.match(nome)
        if m and m.group("n").strip():
            nome = m.group("n")
    nome = LIXO.sub(" ", nome)
    nome = re.sub(r"[\s\-–|:._]+$", "", re.sub(r"\s+", " ", nome)).strip()
    return nome, ano


def cartaz(logo):
    # https://image.tmdb.org/t/p/w600_and_h900_bestv2/x0nvYzQ...jpg -> /x0nvYzQ...jpg
    if logo and "image.tmdb.org" in logo:
        return "/" + logo.rstrip("/").rsplit("/", 1)[-1]
    return None


# ---------------- TMDB ----------------

class Tmdb:
    def __init__(self, chave):
        self.chave = chave.strip()
        self.bearer = self.chave.startswith("eyJ")   # token de leitura (v4) em vez da chave (v3)
        self.trava = threading.Lock()
        self.ultimo = 0.0

    def get(self, caminho, **params):
        # no máximo ~35 pedidos por segundo (o limite do TMDB é ~50)
        with self.trava:
            espera = self.ultimo + 1 / 35 - time.time()
            if espera > 0:
                time.sleep(espera)
            self.ultimo = time.time()
        if not self.bearer:
            params["api_key"] = self.chave
        url = f"{BASE}{caminho}?{urllib.parse.urlencode(params)}"
        req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "StreamPLAY-lista"})
        if self.bearer:
            req.add_header("Authorization", f"Bearer {self.chave}")
        for tentativa in range(4):
            try:
                with urllib.request.urlopen(req, timeout=20) as r:
                    return json.loads(r.read().decode("utf-8"))
            except urllib.error.HTTPError as e:
                if e.code == 401:
                    raise SystemExit("\n  [ERRO] O TMDB recusou a chave. Confira o arquivo tmdb-chave.txt (ou apague para digitar de novo).")
                if e.code == 404:
                    return None
                if e.code == 429:   # pediu para ir mais devagar
                    time.sleep(2 + tentativa * 2)
                    continue
                time.sleep(1 + tentativa)
            except (urllib.error.URLError, TimeoutError, OSError):
                time.sleep(1 + tentativa)
        return None

    def escolher(self, kind, titulo, ano, poster):
        """Procura o título no TMDB e devolve o resultado que bate (ou None). Usado também pelo episodios.py."""
        rota = "/search/movie" if kind == "filme" else "/search/tv"
        params = {"query": titulo, "language": "pt-BR", "include_adult": "false"}
        resultados = []
        if ano:
            params["year" if kind == "filme" else "first_air_date_year"] = ano
            resultados = (self.get(rota, **params) or {}).get("results") or []
            params.pop("year", None)
            params.pop("first_air_date_year", None)
        if not resultados:
            resultados = (self.get(rota, **params) or {}).get("results") or []
        if not resultados:
            return None
        escolhido = None
        if poster:   # o cartaz da lista é do próprio TMDB: se bater, é certeza
            escolhido = next((r for r in resultados if r.get("poster_path") == poster), None)
        if escolhido is None and ano:
            campo = "release_date" if kind == "filme" else "first_air_date"
            escolhido = next((r for r in resultados if (r.get(campo) or "").startswith(ano)), None)
        if escolhido is None:
            if poster and len(resultados) > 1:
                # tem cartaz mas não bateu com nenhum: só aceita se o nome for igual
                escolhido = next((r for r in resultados if norm(r.get("title") or r.get("name")) == norm(titulo)), None)
            else:
                escolhido = resultados[0]
        return escolhido

    def buscar(self, kind, titulo, ano, poster):
        escolhido = self.escolher(kind, titulo, ano, poster)
        if escolhido is None:
            return None
        if kind == "filme":
            d = self.get(f"/movie/{escolhido['id']}", language="pt-BR") or {}
            data = d.get("release_date") or ""
        else:
            d = self.get(f"/tv/{escolhido['id']}", language="pt-BR") or {}
            data = d.get("first_air_date") or ""
        if not d:
            return None
        nota = d.get("vote_average") or 0
        return {
            "descricao": (d.get("overview") or "").strip(),
            "ano": data[:4],
            "genero": ", ".join(g["name"] for g in (d.get("genres") or [])[:3]),
            "nota": f"{nota:.1f}" if nota and (d.get("vote_count") or 0) > 0 else "",
            "fundo": IMG + d["backdrop_path"] if d.get("backdrop_path") else "",
        }


# ---------------- lista ----------------

def valor_seguro(v):
    return re.sub(r"\s+", " ", v.replace('"', "'")).strip()


def tem_campo(attrs, campo):
    return any(attrs.get(n) for n in APELIDOS[campo])


def acrescentar(linha, dados):
    """Coloca na linha #EXTINF os campos que ela ainda não tem (logo depois do -1)."""
    attrs = {k.lower(): v for k, v in ATTR.findall(linha)}
    novos = [f'{k}="{valor_seguro(dados[k])}"' for k in CAMPOS if dados.get(k) and not tem_campo(attrs, k)]
    if not novos:
        return linha, False
    m = re.match(r"(#EXTINF:\s*-?\d+(?:\.\d+)?)", linha, re.I)
    corte = m.end() if m else len("#EXTINF:-1")
    return linha[:corte] + " " + " ".join(novos) + linha[corte:], True


def main():
    if not os.path.exists(LISTA):
        raise SystemExit(f"  [ERRO] Não achei a lista: {LISTA}")
    if not os.path.exists(CHAVE):
        print("  Primeira vez: cole aqui a sua chave da API do TMDB")
        print("  (themoviedb.org > Configurações > API > Chave da API, ou o Token de leitura)")
        chave = input("  Chave: ").strip()
        if not chave:
            raise SystemExit("  Nenhuma chave digitada.")
        with open(CHAVE, "w", encoding="utf-8") as f:
            f.write(chave)
    tmdb = Tmdb(open(CHAVE, encoding="utf-8").read())

    with open(LISTA, encoding="utf-8-sig", errors="replace") as f:
        linhas = f.read().split("\n")

    # 1) o que precisa ser buscado
    alvos = {}        # índice da linha -> chave da busca
    series_vistas = set()
    for i, l in enumerate(linhas):
        if not l.startswith("#EXTINF"):
            continue
        attrs = {k.lower(): v for k, v in ATTR.findall(l)}
        url = next((x.strip() for x in linhas[i + 1:i + 4] if x.strip() and not x.startswith("#")), "")
        kind = tipo(attrs.get("group-title", ""), url)
        if not kind:
            continue
        nome = l.rsplit(",", 1)[-1].strip() if "," in l else ""
        nome = nome or attrs.get("tvg-name", "")
        titulo, ano = titulo_limpo(nome, kind)
        if not titulo:
            continue
        if kind == "serie":
            sk = norm(titulo)
            if sk in series_vistas:
                continue
            series_vistas.add(sk)
        if all(tem_campo(attrs, c) for c in CAMPOS):
            continue
        alvos[i] = (kind, titulo, ano, cartaz(attrs.get("tvg-logo")))

    cache = {}
    if os.path.exists(CACHE):
        with open(CACHE, encoding="utf-8") as f:
            cache = json.load(f)
    chave_de = lambda a: "|".join([a[0], norm(a[1]), a[2] or "", a[3] or ""])
    faltam = sorted({chave_de(a): a for a in alvos.values() if chave_de(a) not in cache}.items())
    filmes = sum(1 for a in alvos.values() if a[0] == "filme")
    print(f"  Lista: {LISTA}")
    print(f"  Filmes para completar: {filmes}  |  séries: {len(alvos) - filmes}")
    print(f"  Já buscados antes: {len(alvos) - len(faltam)}  |  buscar agora no TMDB: {len(faltam)}")

    # 2) busca (em paralelo, salvando o progresso)
    if faltam:
        feitos = 0
        inicio = time.time()
        with ThreadPoolExecutor(max_workers=8) as ex:
            futuros = {ex.submit(tmdb.buscar, *a): k for k, a in faltam}
            for fut in as_completed(futuros):
                k = futuros[fut]
                try:
                    cache[k] = fut.result() or {}
                except SystemExit:
                    raise
                except Exception:
                    cache[k] = {}
                feitos += 1
                if feitos % 200 == 0 or feitos == len(faltam):
                    with open(CACHE + ".tmp", "w", encoding="utf-8") as f:
                        json.dump(cache, f, ensure_ascii=False)
                    os.replace(CACHE + ".tmp", CACHE)
                    passou = time.time() - inicio
                    resta = passou / feitos * (len(faltam) - feitos)
                    print(f"  ... {feitos}/{len(faltam)} buscados (faltam ~{int(resta // 60)} min {int(resta % 60)} s)", flush=True)

    # 3) grava a lista com os campos novos
    mudaram, nao = 0, []
    for i, a in alvos.items():
        dados = cache.get(chave_de(a)) or {}
        if not dados:
            nao.append(f"{'Filme' if a[0] == 'filme' else 'Série'}: {a[1]}" + (f" ({a[2]})" if a[2] else ""))
            continue
        linhas[i], mudou = acrescentar(linhas[i], dados)
        mudaram += mudou
    if mudaram:
        bak = LISTA + ".bak"
        if os.path.exists(bak):
            os.remove(bak)
        os.replace(LISTA, bak)
        with open(LISTA, "w", encoding="utf-8", newline="") as f:
            f.write("\n".join(linhas))
    with open(NAO_ACHADOS, "w", encoding="utf-8-sig") as f:
        f.write("\n".join(sorted(set(nao))) + "\n")
    print()
    print(f"  Pronto: {mudaram} títulos completados na lista.")
    print(f"  Não encontrados no TMDB: {len(set(nao))} (veja nao-encontrados.txt)")
    if mudaram:
        print("  A lista antiga ficou guardada em lista.m3u.bak. Agora é só rodar o publicar-lista.bat.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n  Interrompido. O que já foi buscado ficou salvo: rode de novo para continuar.")
