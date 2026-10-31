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
AJUSTE = os.path.join(d.PASTA, "ajuste-temporadas.txt")
DIFERENTES = os.path.join(d.PASTA, "temporadas-diferentes.txt")
# "S02 = T1 a partir do 6": a S02 da lista é a temporada 1 do TMDB, começando no episódio 6
LINHA_AJUSTE = re.compile(r'^S\s*(\d{1,2})\s*=\s*T\s*(\d{1,2})(?:\s*(?:a\s+partir\s+do|desde\s+o)\s*(?:ep\w*\.?\s*)?(\d{1,4}))?\s*$', re.I)
MODELO_AJUSTE = """# AJUSTE DE TEMPORADAS (para o completar-episodios)
# Algumas séries têm as temporadas numeradas de um jeito no TMDB e de outro na lista
# (ex.: o TMDB junta as Partes 1 e 2 de Lupin numa temporada só). Aqui você diz qual
# temporada do TMDB corresponde a cada temporada da sua lista. O nome na lista NÃO muda
# (continua S02, S03...): só o título, a descrição e a imagem vêm da temporada certa.
#
# Escreva o nome da série e, embaixo, uma linha por temporada da lista, como em Lupin.
# "a partir do 6" = o episódio 1 dessa temporada é o episódio 6 no TMDB.
# Temporada que não aparece aqui continua com o mesmo número.
# O temporadas-diferentes.txt mostra as séries que não batem, já com a sugestão.

Lupin
S01 = T1
S02 = T1 a partir do 6
S03 = T2
S04 = T3
"""
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


def sem_campos(linha):
    """Tira ep-titulo, ep-descricao e ep-imagem da linha (para refazer uma série com as temporadas ajustadas)."""
    return re.sub(r'\s+(?:' + "|".join(CAMPOS) + r')\s*=\s*"[^"]*"', "", linha, flags=re.I)


def ler_ajuste():
    """norm(nome da série) -> {temporada da lista: (temporada no TMDB, quanto somar ao número do episódio)}"""
    if not os.path.exists(AJUSTE):
        with open(AJUSTE, "w", encoding="utf-8-sig") as f:
            f.write(MODELO_AJUSTE)
    ajuste, atual = {}, None
    with open(AJUSTE, encoding="utf-8-sig", errors="replace") as f:
        for l in f:
            l = l.strip()
            if not l or l.startswith("#"):
                continue
            m = LINHA_AJUSTE.match(l)
            if m:
                if atual is not None:
                    atual[int(m.group(1))] = (int(m.group(2)), int(m.group(3)) - 1 if m.group(3) else 0)
            else:
                atual = ajuste.setdefault(d.norm(l), {})
    return {k: v for k, v in ajuste.items() if v}


def sugestao(lista, tmdb):
    """Encaixa as temporadas da lista, em ordem, nas do TMDB pelo número de episódios. Devolve as linhas do ajuste
    ou None se não encaixar certinho (ex.: Lupin: 5+5 episódios = T1 do TMDB com 10)."""
    ts = [(t, n) for t, n in sorted(tmdb.items()) if t > 0 and n > 0]
    if min(lista) != 1:
        return None   # a lista não começa na S01: não dá para saber onde encaixar
    linhas, ti, usados = [], 0, 0
    for s, n in sorted(lista.items()):
        if ti >= len(ts):
            return None
        t, total = ts[ti]
        linhas.append(f"S{s:02} = T{t}" + (f" a partir do {usados + 1}" if usados else ""))
        usados += n
        if usados == total:
            ti, usados = ti + 1, 0
        elif usados > total:
            return None
    if usados:
        return None   # a última temporada da lista ficou pela metade: não bate certinho
    if all(l == f"S{s:02} = T{s}" for l, s in zip(linhas, sorted(lista))):
        return None   # já bate: nada a ajustar
    return linhas


def salvar_cache(cache):
    with open(CACHE + ".tmp", "w", encoding="utf-8") as f:
        json.dump(cache, f, ensure_ascii=False)
    os.replace(CACHE + ".tmp", CACHE)


def em_paralelo(tarefas, fazer, destino, cache, rotulo):
    """Roda fazer(arg) para cada (k, arg) com 8 de cada vez e guarda o resultado em destino[k] (None = falhou, não
    guarda: tenta de novo na próxima vez). Só esta linha mexe no cache — salvar enquanto as outras escreviam nele
    dava "dictionary changed size during iteration"."""
    if not tarefas:
        return
    feitos, inicio = 0, time.time()
    with ThreadPoolExecutor(max_workers=8) as ex:
        futuros = {ex.submit(fazer, a): k for k, a in tarefas}
        for fut in as_completed(futuros):
            try:
                r = fut.result()
                if r is not None:
                    destino[futuros[fut]] = r
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

    # 1) os episódios que ainda não têm nome / descrição / imagem, agrupados por série; as séries do
    #    ajuste-temporadas.txt entram inteiras (refazer), já com a temporada/episódio do TMDB
    ajuste = ler_ajuste()
    alvos = []        # (índice da linha, chave da série, temporada no TMDB, episódio no TMDB, refazer, "S01E02" da lista)
    series = {}       # chave da série -> (título, ano, cartaz)
    na_lista = {}     # chave da série -> {temporada da lista: maior episódio} (para o temporadas-diferentes.txt)
    for i, l in enumerate(linhas):
        if not l.startswith("#EXTINF"):
            continue
        attrs = {k.lower(): v for k, v in d.ATTR.findall(l)}
        completo = all(attrs.get(c) for c in CAMPOS)
        if completo and not ajuste:
            continue
        nome = (l.rsplit(",", 1)[-1].strip() if "," in l else "") or attrs.get("tvg-name", "")
        se = numeros(nome)
        if not se:
            continue
        titulo, ano = d.titulo_limpo(nome, "serie")
        aj = ajuste.get(d.norm(titulo))
        refazer = bool(aj and se[0] in aj)
        if completo and not refazer:
            continue
        url = next((x.strip() for x in linhas[i + 1:i + 4] if x.strip() and not x.startswith("#")), "")
        if not titulo or d.tipo(attrs.get("group-title", ""), url) != "serie":
            continue
        poster = d.cartaz(attrs.get("tvg-logo"))
        sk = "|".join([d.norm(titulo), ano or "", poster or ""])
        series.setdefault(sk, (titulo, ano, poster))
        t, e = (aj[se[0]][0], se[1] + aj[se[0]][1]) if refazer else se
        alvos.append((i, sk, t, e, refazer, f"S{se[0]:02}E{se[1]:02}"))
    # quantos episódios cada série tem em cada temporada (todas as linhas, não só as que faltam)
    for l in linhas:
        if l.startswith("#EXTINF") and "," in l:
            nome = l.rsplit(",", 1)[-1].strip()
            se = numeros(nome)
            if se:
                attrs = {k.lower(): v for k, v in d.ATTR.findall(l)}
                titulo, ano = d.titulo_limpo(nome, "serie")
                sk = "|".join([d.norm(titulo), ano or "", d.cartaz(attrs.get("tvg-logo")) or ""])
                if sk in series:
                    temps = na_lista.setdefault(sk, {})
                    temps[se[0]] = max(temps.get(se[0], 0), se[1])

    cache = {"series": {}, "temporadas": {}}
    if os.path.exists(CACHE):
        with open(CACHE, encoding="utf-8") as f:
            cache.update(json.load(f))
    print(f"  Lista: {d.LISTA}")
    print(f"  Episódios para completar: {len(alvos)}  |  séries: {len(series)}")

    # 2) o código de cada série no TMDB
    def achar_serie(a):
        r = tmdb.escolher("serie", *a)
        return r["id"] if r else 0
    faltam = [(sk, a) for sk, a in series.items() if sk not in cache["series"]]
    print(f"  Séries a procurar no TMDB: {len(faltam)}")
    em_paralelo(faltam, achar_serie, cache["series"], cache, "séries")

    # 3) as temporadas (1 pedido por temporada, com todos os episódios dela)
    def baixar_temporada(a):
        sid, temp = a
        r = tmdb.get(f"/tv/{sid}/season/{temp}", language="pt-BR")
        if r is None:
            return None   # não respondeu (ou a temporada não existe): não guarda, tenta de novo na próxima vez
        eps = {}
        for e in r.get("episodes") or []:
            nome = (e.get("name") or "").strip()
            eps[str(e.get("episode_number"))] = {
                "ep-titulo": "" if GENERICO.match(nome) else nome,
                "ep-descricao": (e.get("overview") or "").strip(),
                "ep-imagem": IMG + e["still_path"] if e.get("still_path") else "",
            }
        return eps
    temporadas = {f"{cache['series'][sk]}|{s}": (cache["series"][sk], s)
                  for _, sk, s, _, _, _ in alvos if cache["series"].get(sk)}
    faltam = [(k, a) for k, a in sorted(temporadas.items()) if k not in cache["temporadas"]]
    print(f"  Temporadas a buscar no TMDB: {len(faltam)} (já buscadas antes: {len(temporadas) - len(faltam)})")
    em_paralelo(faltam, baixar_temporada, cache["temporadas"], cache, "temporadas")
    salvar_cache(cache)

    # 4) grava a lista com os campos novos (nas séries do ajuste, troca os que estavam na temporada errada)
    mudaram, nao = 0, set()
    for i, sk, s, e, refazer, rotulo in alvos:
        sid = cache["series"].get(sk)
        titulo = series[sk][0]
        if not sid:
            nao.add(f"Série não encontrada: {titulo}")
            continue
        antes = linhas[i]
        if refazer:
            linhas[i] = sem_campos(linhas[i])
        dados = (cache["temporadas"].get(f"{sid}|{s}") or {}).get(str(e))
        if not dados:
            nao.add(f"Episódio sem dados no TMDB: {titulo} T{s} E{e}" + (f" (na lista: {rotulo})" if refazer else ""))
        else:
            linhas[i], _ = acrescentar(linhas[i], dados)
        mudaram += linhas[i] != antes

    # 5) séries com temporada que o TMDB não tem (numeradas diferente): mostra como está e sugere o ajuste
    def estrutura(sid):
        r = tmdb.get(f"/tv/{sid}", language="pt-BR")
        return {str(t["season_number"]): t.get("episode_count") or 0 for t in r.get("seasons") or []} if r else None
    suspeitas = {}
    for sk, temps in na_lista.items():
        sid = cache["series"].get(sk)
        if sid and d.norm(series[sk][0]) not in ajuste and any(f"{sid}|{t}" not in cache["temporadas"] for t in temps):
            suspeitas[sk] = sid
    ests = {}
    print(f"  Conferindo as temporadas no TMDB: {len(set(suspeitas.values()))} série(s)")
    em_paralelo(sorted((str(sid), sid) for sid in set(suspeitas.values())), estrutura, ests, cache, "séries")
    blocos = []
    for sk, sid in sorted(suspeitas.items(), key=lambda x: series[x[0]][0].lower()):
        est = {int(t): n for t, n in (ests.get(str(sid)) or {}).items()}
        temps = na_lista[sk]
        if not est or all(t in est for t in temps):
            continue   # o TMDB tem todas as temporadas da lista (ou não respondeu)
        titulo = series[sk][0]
        sug = sugestao(temps, est)
        blocos.append(
            f"{titulo}\n"
            f"  na sua lista: " + ", ".join(f"S{t:02} ({n} eps)" for t, n in sorted(temps.items())) + "\n"
            f"  no TMDB:      " + (", ".join(f"T{t} ({n} eps)" for t, n in sorted(est.items()) if t > 0) or "nenhuma") + "\n"
            + ("  sugestão para o ajuste-temporadas.txt:\n" + "\n".join(["    " + titulo] + ["    " + x for x in sug]) + "\n"
               if sug else "  (sem sugestão: os episódios não batem; pode faltar episódio na lista ou ser temporada nova)\n"))
    with open(DIFERENTES, "w", encoding="utf-8-sig") as f:
        f.write("SÉRIES COM TEMPORADAS QUE O TMDB NÃO TEM (provavelmente numeradas de outro jeito)\n"
                "Para corrigir: copie a sugestão (sem os espaços da frente) para o ajuste-temporadas.txt\n"
                "e rode o completar-episodios.bat de novo.\n\n" + "\n".join(blocos))
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
    if blocos:
        print(f"  Séries com temporadas diferentes do TMDB: {len(blocos)} (veja temporadas-diferentes.txt)")
    if mudaram:
        print("  A lista antiga ficou guardada em lista.m3u.bak. Agora é só rodar o publicar-lista.bat.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n  Interrompido. O que já foi buscado ficou salvo: rode de novo para continuar.")
