"""
Completa os episódios das séries da lista do StreamPLAY com o nome, a descrição e a imagem de cada episódio (TMDB).

- Cada linha de episódio ganha ep-titulo, ep-descricao e ep-imagem (só o que ainda não tiver).
- NÃO muda o nome da linha ("Série S01E02"): o app usa esse nome para lembrar onde cada cliente parou.
- Nunca apaga o que já existe na lista: só acrescenta o que falta.
- Busca no TMDB uma vez por temporada e guarda em tmdb-episodios-cache.json: nas próximas vezes só o que é novo.

Uso: python episodios.py [caminho-da-lista]   (padrão: ..\\public\\lista.m3u)
Usa a mesma chave do TMDB do completar-descricoes (..\\tmdb-chave.txt).
"""
import json
import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # o python roda com -I (sem a pasta do script no caminho)
import descricoes as d   # noqa: E402

CACHE = os.path.join(d.PASTA, "tmdb-episodios-cache.json")
NAO_ACHADOS = os.path.join(d.PASTA, "episodios-nao-encontrados.txt")
IMG = "https://image.tmdb.org/t/p/w300"   # imagem do episódio (16:9), tamanho bom para a lista de episódios
CAMPOS = ("ep-titulo", "ep-descricao", "ep-imagem")
# temporada e episódio: "S01E02", "T1 E2", "1x02" (mesma ideia do app)
SE = re.compile(r'(?:[ST](\d{1,2})\s*[._\-]?\s*E[Pp]?\s*(\d{1,4})|(?<!\d)(\d{1,2})x(\d{1,4}))(?![0-9])', re.I)
GENERICO = re.compile(r'^(epis[oó]dio|episode|cap[ií]tulo)\s*\d+$', re.I)   # "Episódio 3" não acrescenta nada


def numeros(nome):
    m = SE.search(nome)
    if not m:
        return None
    s, e = (m.group(1), m.group(2)) if m.group(1) else (m.group(3), m.group(4))
    return int(s), int(e)


def acrescentar(linha, dados):
    """Coloca na linha #EXTINF os campos de episódio que ela ainda não tem (logo depois do -1)."""
    attrs = {k.lower(): v for k, v in d.ATTR.findall(linha)}
    novos = [f'{k}="{d.valor_seguro(dados[k])}"' for k in CAMPOS if dados.get(k) and not attrs.get(k)]
    if not novos:
        return linha, False
    m = re.match(r"(#EXTINF:\s*-?\d+(?:\.\d+)?)", linha, re.I)
    corte = m.end() if m else len("#EXTINF:-1")
    return linha[:corte] + " " + " ".join(novos) + linha[corte:], True


def salvar_cache(cache):
    with open(CACHE + ".tmp", "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(CACHE + ".tmp", CACHE)


def em_paralelo(tarefas, fazer, cache, rotulo):
    """Roda fazer(k, arg) para cada (k, arg) com 8 de cada vez, mostrando o andamento e salvando o cache."""
    if not tarefas:
        return
    feitos, inicio = 0, time.time()
    with ThreadPoolExecutor(max_workers=8) as ex:
        futuros = {ex.submit(fazer, k, a): k for k, a in tarefas}
        for fut in as_completed(futuros):
            try:
                fut.result()
            except SystemExit:
                raise
            except Exception:
                pass
            feitos += 1
            if feitos % 100 == 0 or feitos == len(tarefas):
                salvar_cache(cache)
                passou = time.time() - inicio
                resta = passou / feitos * (len(tarefas) - feitos)
                print(f"  ... {rotulo}: {feitos}/{len(tarefas)} (faltam ~{int(resta // 60)} min {int(resta % 60)} s)", flush=True)


def main():
    if not os.path.exists(d.LISTA):
        raise SystemExit(f"  [ERRO] Não achei a lista: {d.LISTA}")
    if not os.path.exists(d.CHAVE):
        print("  Primeira vez: cole aqui a sua chave da API do TMDB")
        print("  (themoviedb.org > Configurações > API > Chave da API, ou o Token de leitura)")
        chave = input("  Chave: ").strip()
        if not chave:
            raise SystemExit("  Nenhuma chave digitada.")
        with open(d.CHAVE, "w", encoding="utf-8") as f:
            f.write(chave)
    tmdb = d.Tmdb(open(d.CHAVE, encoding="utf-8").read())

    with open(d.LISTA, encoding="utf-8-sig", errors="replace") as f:
        linhas = f.read().split("\n")

    # 1) os episódios que ainda não têm nome / descrição / imagem, agrupados por série
    alvos = []        # (índice da linha, chave da série, temporada, episódio)
    series = {}       # chave da série -> (título, ano, cartaz)
    for i, l in enumerate(linhas):
        if not l.startswith("#EXTINF"):
            continue
        attrs = {k.lower(): v for k, v in d.ATTR.findall(l)}
        if all(attrs.get(c) for c in CAMPOS):
            continue
        url = next((x.strip() for x in linhas[i + 1:i + 4] if x.strip() and not x.startswith("#")), "")
        if d.tipo(attrs.get("group-title", ""), url) != "serie":
            continue
        nome = (l.rsplit(",", 1)[-1].strip() if "," in l else "") or attrs.get("tvg-name", "")
        se = numeros(nome)
        titulo, ano = d.titulo_limpo(nome, "serie")
        if not se or not titulo:
            continue
        poster = d.cartaz(attrs.get("tvg-logo"))
        sk = "|".join([d.norm(titulo), ano or "", poster or ""])
        series.setdefault(sk, (titulo, ano, poster))
        alvos.append((i, sk, se[0], se[1]))

    cache = {"series": {}, "temporadas": {}}
    if os.path.exists(CACHE):
        with open(CACHE, encoding="utf-8") as f:
            cache.update(json.load(f))
    print(f"  Lista: {d.LISTA}")
    print(f"  Episódios para completar: {len(alvos)}  |  séries: {len(series)}")

    # 2) o código de cada série no TMDB
    def achar_serie(sk, a):
        r = tmdb.escolher("serie", *a)
        cache["series"][sk] = r["id"] if r else 0
    faltam = [(sk, a) for sk, a in series.items() if sk not in cache["series"]]
    print(f"  Séries a procurar no TMDB: {len(faltam)}")
    em_paralelo(faltam, achar_serie, cache, "séries")

    # 3) as temporadas (1 pedido por temporada, com todos os episódios dela)
    def baixar_temporada(chave, a):
        sid, temp = a
        r = tmdb.get(f"/tv/{sid}/season/{temp}", language="pt-BR") or {}
        eps = {}
        for e in r.get("episodes") or []:
            nome = (e.get("name") or "").strip()
            eps[str(e.get("episode_number"))] = {
                "ep-titulo": "" if GENERICO.match(nome) else nome,
                "ep-descricao": (e.get("overview") or "").strip(),
                "ep-imagem": IMG + e["still_path"] if e.get("still_path") else "",
            }
        cache["temporadas"][chave] = eps
    temporadas = {f"{cache['series'][sk]}|{s}": (cache["series"][sk], s)
                  for _, sk, s, _ in alvos if cache["series"].get(sk)}
    faltam = [(k, a) for k, a in sorted(temporadas.items()) if k not in cache["temporadas"]]
    print(f"  Temporadas a buscar no TMDB: {len(faltam)} (já buscadas antes: {len(temporadas) - len(faltam)})")
    em_paralelo(faltam, baixar_temporada, cache, "temporadas")
    salvar_cache(cache)

    # 4) grava a lista com os campos novos
    mudaram, nao = 0, set()
    for i, sk, s, e in alvos:
        sid = cache["series"].get(sk)
        titulo = series[sk][0]
        if not sid:
            nao.add(f"Série não encontrada: {titulo}")
            continue
        dados = (cache["temporadas"].get(f"{sid}|{s}") or {}).get(str(e))
        if not dados:
            nao.add(f"Episódio sem dados no TMDB: {titulo} T{s} E{e}")
            continue
        linhas[i], mudou = acrescentar(linhas[i], dados)
        mudaram += mudou
    if mudaram:
        bak = d.LISTA + ".bak"
        if os.path.exists(bak):
            os.remove(bak)
        os.replace(d.LISTA, bak)
        with open(d.LISTA, "w", encoding="utf-8", newline="") as f:
            f.write("\n".join(linhas))
    with open(NAO_ACHADOS, "w", encoding="utf-8-sig") as f:
        f.write("\n".join(sorted(nao)) + "\n")
    print()
    print(f"  Pronto: {mudaram} episódios completados na lista.")
    print(f"  Sem dados no TMDB: {len(nao)} (veja episodios-nao-encontrados.txt)")
    if mudaram:
        print("  A lista antiga ficou guardada em lista.m3u.bak. Agora é só rodar o publicar-lista.bat.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n  Interrompido. O que já foi buscado ficou salvo: rode de novo para continuar.")
