"""
Verifica os links da lista do StreamPLAY e mostra quais estão quebrados.

- Canais (.m3u8): abre a lista do canal e confere se o primeiro pedaço de vídeo responde.
- Filmes e episódios: pede só o começo do arquivo (1 KB), sem baixar o vídeo.
- Antes de dar um link como quebrado, testa de novo mais duas vezes, alguns minutos depois.
- Testa 20 links ao mesmo tempo. Se o fornecedor começar a recusar (muitos "acesso negado" ou "não respondeu"
  que antes não apareciam), diminua com --simultaneos: ele pode bloquear a conta se forem muitos.
- Nunca mexe na sua lista: gera links-quebrados.html e uma cópia sem os quebrados, para você conferir.
- Guarda o que já testou em links-cache.json: da próxima vez começa pelos novos e pelos que tinham falhado.

Uso: python verificar.py [--o canais,filmes,series] [--simultaneos 20] [--pausa 0.1] [--espera 120] [--timeout 15]
"""
import argparse
import html
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
from concurrent.futures import ThreadPoolExecutor, wait
from datetime import datetime

PASTA = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # lista-online
PUBLICA = os.path.join(PASTA, "public")
CACHE = os.path.join(PASTA, "links-cache.json")
RELATORIO = os.path.join(PASTA, "links-quebrados.html")
AGENTE = "VLC/3.0.24 LibVLC/3.0.24"   # o mesmo que o player usa

ATTR = re.compile(r'([\w-]+)\s*=\s*"([^"]*)"')
EP = re.compile(r'^(?P<n>.*?)[\s._\-–|:]*(?:[ST](?P<s>\d{1,2})\s*[._\-]?\s*E[Pp]?\s*(?P<e>\d{1,4})|(?P<s2>\d{1,2})x(?P<e2>\d{1,4}))(?![0-9])', re.I)

OK, NAO_EXISTE, NEGADO, SEM_RESPOSTA, NAO_VIDEO, SEM_VIDEO, ERRO = (
    "ok", "não existe mais", "acesso negado", "não respondeu", "não é vídeo", "canal sem vídeo", "erro do servidor")


def norm(s):
    s = unicodedata.normalize("NFD", s or "")
    return "".join(c for c in s if unicodedata.category(c) != "Mn").lower().strip()


# ---------------- leitura da lista ----------------

class Item:
    def __init__(self, arquivo, linha, url, nome, grupo, tipo, opcoes):
        self.arquivo, self.linha, self.url, self.nome, self.grupo, self.tipo, self.opcoes = arquivo, linha, url, nome, grupo, tipo, opcoes


def tipo_de(grupo, url, arquivo):
    g = norm(grupo)
    if re.match(r"(filmes?|movies?|vod)(?![a-z])", g) or "/movie/" in url:
        return "filme"
    if re.match(r"(series?|seriados?)(?![a-z])", g) or "/series/" in url:
        return "serie"
    return "canal"


def ler_lista(caminho):
    itens = []
    if not os.path.exists(caminho):
        return itens
    with open(caminho, encoding="utf-8-sig", errors="replace") as f:
        linhas = f.read().split("\n")
    inf, opcoes = None, {}
    for i, bruta in enumerate(linhas):
        l = bruta.strip()
        if not l:
            continue
        if l.upper().startswith("#EXTINF"):
            inf, opcoes = (i, l), {}
        elif l.upper().startswith("#EXTVLCOPT:"):
            k, _, v = l[11:].partition("=")
            opcoes[k.strip().lower()] = v.strip()
        elif not l.startswith("#") and inf:
            attrs = {k.lower(): v for k, v in ATTR.findall(inf[1])}
            nome = inf[1].rsplit(",", 1)[-1].strip() if "," in inf[1] else attrs.get("tvg-name", l)
            grupo = attrs.get("group-title", "")
            itens.append(Item(caminho, inf[0], l, nome, grupo, tipo_de(grupo, l, caminho), opcoes))
            inf = None
    return itens


def escolher_episodios(itens):
    """Das séries, só o primeiro episódio de cada temporada (se ele está fora, o resto quase sempre também está)."""
    melhor = {}
    for it in itens:
        m = EP.match(it.nome)
        serie, temp, ep = (m.group("n"), int(m.group("s") or m.group("s2")), int(m.group("e") or m.group("e2"))) if m and m.group("n").strip() else (it.nome, 1, 0)
        k = (norm(serie), temp)
        if k not in melhor or ep < melhor[k][0]:
            melhor[k] = (ep, it)
    return [v[1] for v in melhor.values()]


# ---------------- teste de um link ----------------

def pedir(url, item, args, tamanho=1024, com_range=True):
    cab = {"User-Agent": item.opcoes.get("http-user-agent", AGENTE), "Icy-MetaData": "0"}
    if com_range:
        cab["Range"] = f"bytes=0-{tamanho - 1}"
    if item.opcoes.get("http-referrer"):
        cab["Referer"] = item.opcoes["http-referrer"]
    req = urllib.request.Request(url, headers=cab)
    try:
        with urllib.request.urlopen(req, timeout=args.timeout) as r:
            return r.geturl(), r.headers.get("Content-Type", ""), r.read(tamanho)
    except urllib.error.HTTPError as e:
        if e.code == 416 and com_range:   # transmissão ao vivo que não aceita "só um pedaço": pede normal e lê o começo
            return pedir(url, item, args, tamanho, com_range=False)
        raise


def parece_html(tipo, dados):
    ini = dados[:200].lstrip().lower()
    return "text/html" in tipo.lower() or ini.startswith(b"<!doctype") or ini.startswith(b"<html")


def testar(item, args):
    """Devolve (estado, detalhe)."""
    try:
        final, ctype, dados = pedir(item.url, item, args)
        if dados.lstrip().startswith(b"#EXTM3U") or "mpegurl" in ctype.lower():
            return testar_hls(final, item, args, profundidade=0)
        if not dados:
            return SEM_VIDEO, "o servidor respondeu, mas sem nenhum dado"
        if parece_html(ctype, dados):
            return NAO_VIDEO, "devolveu uma página no lugar do vídeo"
        return OK, ""
    except urllib.error.HTTPError as e:
        return erro_http(e.code)
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
        return SEM_RESPOSTA, motivo_rede(getattr(e, "reason", e), args)


def motivo_rede(e, args):
    t = str(e).lower()
    if isinstance(e, TimeoutError) or "timed out" in t:
        return f"nada em {int(args.timeout)} s"
    if isinstance(e, ConnectionRefusedError) or "refused" in t or "10061" in t:
        return "o servidor recusou a conexão"
    if "getaddrinfo" in t or "11001" in t or "name or service" in t:
        return "endereço não encontrado (o site saiu do ar ou sem internet)"
    if isinstance(e, ValueError):
        return "link inválido"
    return str(e)[:120]


def testar_hls(url, item, args, profundidade):
    # lê a lista do canal inteira (é pequena) e testa o primeiro pedaço de vídeo
    _, _, texto = pedir(url, item, args, tamanho=512 * 1024)
    linhas = [l.strip() for l in texto.decode("utf-8", "replace").splitlines()]
    proximo = next((l for l in linhas if l and not l.startswith("#")), None)
    if proximo is None:
        return SEM_VIDEO, "a lista do canal está vazia"
    alvo = urllib.parse.urljoin(url, proximo)
    variante = any(l.upper().startswith("#EXT-X-STREAM-INF") for l in linhas)
    try:
        if variante and profundidade < 2:   # lista de qualidades: entra na primeira
            return testar_hls(alvo, item, args, profundidade + 1)
        _, ctype, dados = pedir(alvo, item, args)
        if not dados:
            return SEM_VIDEO, "o pedaço de vídeo veio vazio"
        if parece_html(ctype, dados):
            return NAO_VIDEO, "o pedaço de vídeo é uma página"
        return OK, ""
    except urllib.error.HTTPError as e:
        estado, detalhe = erro_http(e.code)
        return (SEM_VIDEO if estado == NAO_EXISTE else estado), "pedaço de vídeo: " + detalhe


def erro_http(codigo):
    if codigo in (404, 410):
        return NAO_EXISTE, f"erro {codigo}"
    if codigo in (401, 403):
        return NEGADO, f"erro {codigo} (login/token do fornecedor vencido ou link bloqueado)"
    return ERRO, f"erro {codigo}"


# ---------------- rodada de testes ----------------

def rodar(itens, args, titulo, resultados):
    trava = threading.Lock()
    feitos = [0]
    inicio = time.time()

    def um(it):
        r = testar(it, args)
        time.sleep(args.pausa)
        with trava:
            resultados[it.url] = r
            feitos[0] += 1
            n = feitos[0]
            if n % 25 == 0 or n == len(itens):
                passou = time.time() - inicio
                resta = passou / n * (len(itens) - n)
                print(f"  {titulo}: {n}/{len(itens)}  (faltam ~{int(resta // 60)} min {int(resta % 60)} s)", flush=True)

    ex = ThreadPoolExecutor(max_workers=args.simultaneos)
    pendentes = {ex.submit(um, it) for it in itens}
    try:
        while pendentes:   # espera em pedaços curtos para o Ctrl+C funcionar no Windows
            _, pendentes = wait(pendentes, timeout=0.5)
    finally:
        ex.shutdown(wait=False, cancel_futures=True)
    return resultados


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--o", help="canais,filmes,series (sem isso, pergunta)")
    ap.add_argument("--simultaneos", type=int, default=20, help="testes ao mesmo tempo (padrão 20)")
    ap.add_argument("--pausa", type=float, default=0.1, help="segundos de pausa depois de cada teste")
    ap.add_argument("--espera", type=float, default=120, help="segundos antes de testar de novo os que falharam")
    ap.add_argument("--timeout", type=float, default=15, help="segundos esperando cada resposta")
    ap.add_argument("--limite", type=int, default=0, help="testa no máximo esse número de links nesta vez (0 = todos)")
    args = ap.parse_args()

    lista = ler_lista(os.path.join(PUBLICA, "lista.m3u"))
    canais = ler_lista(os.path.join(PUBLICA, "canais.m3u"))
    todos = lista + canais
    if not todos:
        raise SystemExit(f"  [ERRO] Não achei a lista em {PUBLICA}")
    por_tipo = {t: [i for i in todos if i.tipo == t] for t in ("canal", "filme", "serie")}
    temporadas = escolher_episodios(por_tipo["serie"])

    if args.o:
        escolha = {x.strip() for x in args.o.split(",")}
    else:
        print(f"  O que verificar?")
        print(f"   1 - Canais ({len(por_tipo['canal'])} links, rápido)")
        print(f"   2 - Filmes ({len(por_tipo['filme'])} links)")
        print(f"   3 - Séries (o 1º episódio de cada temporada: {len(temporadas)} links)")
        print(f"   4 - Tudo")
        r = input("  Digite o número e Enter: ").strip()
        escolha = {"1": {"canais"}, "2": {"filmes"}, "3": {"series"}, "4": {"canais", "filmes", "series"}}.get(r)
        if not escolha:
            raise SystemExit("  Nenhuma opção escolhida.")
    alvos = (por_tipo["canal"] if "canais" in escolha else []) + (por_tipo["filme"] if "filmes" in escolha else []) \
        + (temporadas if "series" in escolha else [])
    # o mesmo link em várias categorias: testa uma vez
    unicos = list({i.url: i for i in alvos}.values())

    cache = {}
    if os.path.exists(CACHE):
        with open(CACHE, encoding="utf-8") as f:
            cache = json.load(f)
    # primeiro os nunca testados, depois os que tinham falhado, depois os testados há mais tempo
    unicos.sort(key=lambda i: (i.url in cache, cache.get(i.url, {}).get("estado") == OK, cache.get(i.url, {}).get("quando", "")))
    if args.limite:
        unicos = unicos[:args.limite]
    por_teste = 1.2 + args.pausa
    est = len(unicos) * por_teste / max(1, args.simultaneos)
    print(f"\n  Testando {len(unicos)} links, {args.simultaneos} por vez (estimativa: ~{int(est // 3600)} h {int(est % 3600 // 60)} min).")
    print("  Pode fechar quando quiser (Ctrl+C): o que já foi testado fica guardado.\n")

    def salvar():
        with open(CACHE + ".tmp", "w", encoding="utf-8") as f:
            json.dump(cache, f, ensure_ascii=False)
        os.replace(CACHE + ".tmp", CACHE)

    agora = lambda: datetime.now().isoformat(timespec="seconds")
    finais = {}   # url -> (estado, detalhe) da última tentativa
    tentativas = {}
    try:
        rodar(unicos, args, "Testando", finais)
        for i in unicos:
            tentativas[i.url] = 1
        falhos = [i for i in unicos if finais[i.url][0] != OK]
        for rodada in (2, 3):   # antes de dar como quebrado, mais duas chances
            if not falhos:
                break
            print(f"\n  {len(falhos)} links falharam. Testando de novo daqui a {int(args.espera)} s (tentativa {rodada} de 3)...")
            time.sleep(args.espera)
            rodar(falhos, args, f"Tentativa {rodada}", finais)
            for i in falhos:
                tentativas[i.url] = rodada
            falhos = [i for i in falhos if finais[i.url][0] != OK]
    except KeyboardInterrupt:
        print("\n  Interrompido: o relatório sai só com o que já foi testado até agora.")
    finally:
        for url, (estado, detalhe) in finais.items():
            antes = cache.get(url, {})
            desde = antes.get("desde") if antes.get("estado", OK) != OK and estado != OK else (agora() if estado != OK else None)
            cache[url] = {"estado": estado, "detalhe": detalhe, "quando": agora(), "desde": desde, "tentativas": tentativas.get(url, 1)}
        salvar()

    quebrados = [i for i in alvos if cache.get(i.url, {}).get("estado", OK) != OK]
    gerar_relatorio(quebrados, cache, len({i.url for i in alvos}), escolha)
    copias = gerar_listas_limpas({i.url for i in quebrados})
    print(f"\n  Pronto: {len({i.url for i in quebrados})} links quebrados de {len({i.url for i in alvos})}.")
    print(f"  Relatório: {RELATORIO}")
    for c in copias:
        print(f"  Lista sem os quebrados: {c}")
    if copias:
        print("  Sua lista não foi alterada. Confira o relatório e, se quiser, troque a lista pela cópia limpa e publique.")


# ---------------- saída ----------------

def gerar_listas_limpas(quebrados):
    """Copia lista.m3u e canais.m3u sem as entradas quebradas (a #EXTINF, as #EXTVLCOPT e o link)."""
    feitas = []
    for nome in ("lista.m3u", "canais.m3u"):
        origem = os.path.join(PUBLICA, nome)
        if not os.path.exists(origem):
            continue
        with open(origem, encoding="utf-8-sig", errors="replace") as f:
            linhas = f.read().split("\n")
        if not any(l.strip() in quebrados for l in linhas):
            continue
        saida, pendentes = [], []
        for l in linhas:
            s = l.strip()
            if s.upper().startswith("#EXTINF") or s.upper().startswith("#EXTVLCOPT") or s.upper().startswith("#EXTGRP"):
                pendentes.append(l)
                continue
            if s and not s.startswith("#"):
                if s not in quebrados:
                    saida.extend(pendentes)
                    saida.append(l)
                pendentes = []
                continue
            saida.extend(pendentes)
            pendentes = []
            saida.append(l)
        saida.extend(pendentes)
        destino = os.path.join(PASTA, nome.replace(".m3u", "-sem-quebrados.m3u"))
        with open(destino, "w", encoding="utf-8", newline="") as f:
            f.write("\n".join(saida))
        feitas.append(destino)
    return feitas


def gerar_relatorio(quebrados, cache, total, escolha):
    nomes = {"canal": "Canais", "filme": "Filmes", "serie": "Séries (1º episódio de cada temporada)"}
    grupos = {}
    for i in quebrados:
        grupos.setdefault((i.tipo, i.grupo or "(sem categoria)"), []).append(i)
    fmt = lambda iso: datetime.fromisoformat(iso).strftime("%d/%m %H:%M") if iso else ""
    contagem = {}
    for i in quebrados:
        contagem.setdefault(cache[i.url]["estado"], set()).add(i.url)
    partes = []
    for tipo in ("canal", "filme", "serie"):
        cats = sorted((k for k in grupos if k[0] == tipo), key=lambda k: norm(k[1]))
        if not cats:
            continue
        partes.append(f"<h2>{nomes[tipo]}</h2>")
        for k in cats:
            linhas = "".join(
                f"<tr><td>{html.escape(i.nome)}</td><td><span class='e'>{html.escape(cache[i.url]['estado'])}</span>"
                f"<div class='d'>{html.escape(cache[i.url].get('detalhe') or '')}</div></td>"
                f"<td class='q'>{fmt(cache[i.url].get('desde'))}</td><td class='u'>{html.escape(i.url)}</td></tr>"
                for i in sorted(grupos[k], key=lambda x: norm(x.nome)))
            partes.append(f"<h3>{html.escape(k[1])} <small>{len(grupos[k])}</small></h3>"
                          f"<table><tr><th>Nome</th><th>Problema</th><th>Fora desde</th><th>Link</th></tr>{linhas}</table>")
    resumo = "".join(f"<li><b>{len(v)}</b> {html.escape(k)}</li>" for k, v in sorted(contagem.items(), key=lambda x: -len(x[1])))
    dica = ""
    if NEGADO in contagem and len(contagem[NEGADO]) > max(5, len({i.url for i in quebrados}) // 2):
        dica = "<p class='aviso'>A maioria deu <b>acesso negado</b>: isso costuma ser o login ou o token do fornecedor vencido, e não os vídeos em si. Confira com o fornecedor antes de tirar os links da lista.</p>"
    corpo = "".join(partes) or "<p class='ok'>Nenhum link quebrado. 🎉</p>"
    pagina = f"""<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Links quebrados</title><style>
body{{font:15px/1.5 system-ui,Segoe UI,sans-serif;background:#141414;color:#e5e5e5;margin:0;padding:24px 16px}}
main{{max-width:1100px;margin:auto}} h1{{margin:0 0 4px}} h2{{margin:28px 0 8px;color:#fff}} h3{{margin:18px 0 6px;color:#ddd}}
h3 small{{background:#e50914;color:#fff;border-radius:10px;padding:1px 8px;font-size:12px;vertical-align:2px}}
.m{{color:#999}} ul{{margin:8px 0}} table{{width:100%;table-layout:fixed;border-collapse:collapse;background:#1c1c1c;border-radius:8px;overflow:hidden}}
th:nth-child(1){{width:30%}} th:nth-child(2){{width:26%}} th:nth-child(3){{width:12%}}
th,td{{text-align:left;padding:7px 10px;border-top:1px solid #2a2a2a;vertical-align:top}} th{{color:#999;font-weight:600;font-size:13px;border-top:0}}
.e{{color:#ff6b6b;font-weight:600}} .d{{color:#888;font-size:12.5px}} .q{{white-space:nowrap;color:#aaa}}
.u{{font-size:12px;color:#777;word-break:break-all;max-width:360px}} .aviso{{background:#3a2a00;border-left:4px solid #f5b400;padding:10px 14px;border-radius:6px}}
.ok{{font-size:18px}}
</style></head><body><main>
<h1>Links quebrados</h1>
<div class="m">Verificado em {datetime.now().strftime('%d/%m/%Y %H:%M')} · {len({i.url for i in quebrados})} quebrados de {total} links testados · cada um falhou 3 vezes seguidas</div>
<ul>{resumo}</ul>{dica}{corpo}
</main></body></html>"""
    with open(RELATORIO, "w", encoding="utf-8") as f:
        f.write(pagina)


if __name__ == "__main__":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    main()
