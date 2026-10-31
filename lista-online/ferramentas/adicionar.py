"""
Acrescenta episódios de séries na lista do StreamPLAY a partir de um .txt simples (episodios-novos.txt):

    Nome da Série
    S04E01 http://link-do-episodio-1.mp4
    S04E02 http://link-do-episodio-2.mp4

    Outra Série
    S03E01 http://...

- Uma linha sem link é o nome da série; as linhas "SxxEyy link" logo abaixo são os episódios dela.
- Se a série já existe na lista: usa o mesmo nome, capa e categoria dela e coloca os episódios logo abaixo dos que
  ela já tem (não fica misturado). Se não existe: pergunta a categoria e cria a série no fim da lista.
- Episódio que já existe com o mesmo link é pulado; com outro link, pergunta se troca o link.
- Nunca apaga nada da lista. A lista antiga fica em lista.m3u.bak.

Uso: python adicionar.py [episodios-novos.txt] [lista.m3u]
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # o python roda com -I (sem a pasta do script no caminho)
import descricoes as d   # noqa: E402
import episodios as ep   # noqa: E402

TXT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(d.PASTA, "episodios-novos.txt")
LISTA = sys.argv[2] if len(sys.argv) > 2 else d.LISTA
FEITOS = os.path.join(os.path.dirname(TXT), "episodios-adicionados.txt")

# "S04E01 link", "S4 E1 - link", "T04EP01 link", "4x01 link"
LINHA_EP = re.compile(r'^(?:[ST]\s*(\d{1,2})\s*[._\-]?\s*E(?:P|PISODIO|PISÓDIO)?\s*[._\-]?\s*(\d{1,4})|(\d{1,2})\s*x\s*(\d{1,4}))(?![0-9])(.*)$', re.I)
LINK = re.compile(r'(?:https?|rtmp|rtsp)://\S+', re.I)
MODELO = """# EPISÓDIOS NOVOS PARA A LISTA DO STREAMPLAY
# Escreva embaixo destas linhas de explicação. Na primeira linha, o nome da série;
# embaixo dela, um episódio por linha: S04E01 e o link na frente. Pode colocar várias
# séries, uma depois da outra. Salve (Ctrl+S) e feche o Bloco de Notas para continuar.
# Exemplo:      Nome da Série
#               S04E01 http://link-do-episodio-1.mp4
#               S04E02 http://link-do-episodio-2.mp4

"""
# o texto de explicação (deste modelo e do anterior) é ignorado; qualquer outra linha vale, mesmo com # na frente
EXPLICACAO = {l.strip("# ").strip() for l in (MODELO + """
# Primeiro o nome da série numa linha; embaixo, um episódio por linha: S04E01 e o link na frente.
# Pode colocar várias séries, uma depois da outra. Linhas que começam com # são ignoradas.
# Salve (Ctrl+S) e feche o Bloco de Notas para continuar.
# Exemplo:
#   Nome da Série
#   S04E01 http://link-do-episodio-1.mp4
#   S04E02 http://link-do-episodio-2.mp4
""").splitlines()} | {"Exemplo:      Nome da Série", "Exemplo: Nome da Série"}


def perguntar(texto):
    try:
        return input(texto).strip()
    except EOFError:
        raise SystemExit("\n  Sem resposta. Nada foi mudado.")


def sim(texto):
    return perguntar(texto + " [S/N] ").lower().startswith("s")


def ler_txt():
    """Devolve [(nome digitado, [(temporada, episódio, link)])] e a lista de linhas com problema."""
    with open(TXT, "rb") as f:
        bruto = f.read()
    try:
        texto = bruto.decode("utf-8-sig")
    except UnicodeDecodeError:
        texto = bruto.decode("cp1252")   # Bloco de Notas antigo (ANSI)
    series, erros, atual = [], [], None
    for n, l in enumerate(texto.splitlines(), 1):
        l = l.strip().lstrip("#").strip()
        if not l or l in EXPLICACAO:
            continue
        m = LINHA_EP.match(l)
        if m:
            s, e = (m.group(1), m.group(2)) if m.group(1) else (m.group(3), m.group(4))
            link = LINK.search(m.group(5) or "")
            if not link:
                erros.append(f"linha {n}: sem link -> {l}")
            elif atual is None:
                erros.append(f"linha {n}: episódio antes do nome da série -> {l}")
            else:
                atual[1].append((int(s), int(e), link.group(0)))
            continue
        if LINK.search(l):
            erros.append(f"linha {n}: falta o S01E01 antes do link -> {l}")
            continue
        atual = (l, [])
        series.append(atual)
    return [s for s in series if s[1]], erros


def nome_da_serie(linha):
    """Nome da série de uma linha #EXTINF de episódio (como o app faz: o que vem antes do S01E02)."""
    nome = (linha.rsplit(",", 1)[-1].strip() if "," in linha else "")
    if not nome:
        nome = {k.lower(): v for k, v in d.ATTR.findall(linha)}.get("tvg-name", "")
    m = d.EP.match(nome)
    if not m or not m.group("n").strip():
        return None
    return re.sub(r"[\s\-–|:._]+$", "", m.group("n")).strip()


def mapa_series(linhas):
    """norm(nome) -> {nome, ultima (índice da linha do link do último episódio), modelo (linha #EXTINF), eps}"""
    series = {}
    for i, l in enumerate(linhas):
        if not l.startswith("#EXTINF"):
            continue
        attrs = {k.lower(): v for k, v in d.ATTR.findall(l)}
        j = next((k for k in range(i + 1, min(i + 4, len(linhas))) if linhas[k].strip() and not linhas[k].startswith("#")), None)
        if j is None or d.tipo(attrs.get("group-title", ""), linhas[j].strip()) != "serie":
            continue
        nome = nome_da_serie(l)
        se = ep.numeros(l.rsplit(",", 1)[-1]) if nome else None
        if not se:
            continue
        s = series.setdefault(d.norm(nome), {"nome": nome, "eps": {}})
        s["ultima"], s["modelo"] = j, l
        s["eps"][se] = (i, j)
    return series


def escolher_serie(digitado, series):
    """A série da lista com esse nome; se não achar, mostra as parecidas para escolher (ou None = série nova)."""
    chave = d.norm(digitado)
    if chave in series:
        return chave
    parecidas = sorted(k for k in series if chave in k or k in chave)[:15]
    print(f'\n  Não achei a série "{digitado}" com esse nome exato na lista.')
    if parecidas:
        for n, k in enumerate(parecidas, 1):
            print(f"    {n:2}) {series[k]['nome']}  ({len(series[k]['eps'])} episódios)")
        r = perguntar("  Digite o número da série certa, ou Enter para criar como série nova: ")
        if r.isdigit() and 1 <= int(r) <= len(parecidas):
            return parecidas[int(r) - 1]
    return None


def escolher_categoria(linhas):
    grupos = {}
    for l in linhas:
        if l.startswith("#EXTINF"):
            g = {k.lower(): v for k, v in d.ATTR.findall(l)}.get("group-title", "")
            if d.tipo(g, "") == "serie":
                grupos[g] = grupos.get(g, 0) + 1
    ordem = sorted(grupos, key=lambda g: -grupos[g])
    for n, g in enumerate(ordem, 1):
        print(f"    {n:2}) {g}")
    while True:
        r = perguntar("  Número da categoria: ")
        if r.isdigit() and 1 <= int(r) <= len(ordem):
            return ordem[int(r) - 1]


def linha_ep(nome, s, e, logo, grupo):
    titulo = f"{nome} S{s:02}E{e:02}"
    logo = f' tvg-logo="{logo}"' if logo else ""
    return f'#EXTINF:-1 tvg-name="{d.valor_seguro(titulo)}"{logo} group-title="{grupo}",{titulo}'


def main():
    if not os.path.exists(TXT):
        with open(TXT, "w", encoding="utf-8-sig") as f:
            f.write(MODELO)
        raise SystemExit(f"  Criei o arquivo {os.path.basename(TXT)}: coloque os episódios nele e rode de novo.")
    if not os.path.exists(LISTA):
        raise SystemExit(f"  [ERRO] Não achei a lista: {LISTA}")
    pedidos, erros = ler_txt()
    if erros:
        print("  Linhas que não entendi (ficam de fora):")
        for e in erros:
            print("    " + e)
        print()
    if not pedidos:
        raise SystemExit("  Nenhum episódio para adicionar no arquivo.")

    with open(LISTA, encoding="utf-8-sig", errors="replace", newline="") as f:
        linhas = f.read().split("\n")
    fim = "\r" if linhas and linhas[0].endswith("\r") else ""   # mantém o fim de linha que a lista já usa
    series = mapa_series(linhas)

    depois = {}     # índice da linha -> linhas novas que entram logo depois dela
    no_fim = []     # séries novas
    trocar = []     # (índice da linha do link, link novo, rótulo)
    resumo, iguais = [], 0
    for digitado, eps in pedidos:
        k = escolher_serie(digitado, series)
        if k is None:
            print(f'\n  "{digitado}" vai entrar como série NOVA. Em qual categoria?')
            grupo, logo, nome, existentes = escolher_categoria(linhas), "", digitado, {}
        else:
            s = series[k]
            attrs = {a.lower(): v for a, v in d.ATTR.findall(s["modelo"])}
            grupo, logo, nome, existentes = attrs.get("group-title", ""), attrs.get("tvg-logo", ""), s["nome"], s["eps"]
        novos = {}
        for t, e, link in eps:   # o mesmo episódio duas vezes no .txt: vale o último
            novos[(t, e)] = link
        entram = []
        for (t, e), link in sorted(novos.items()):
            if (t, e) in existentes:
                i, j = existentes[(t, e)]
                if linhas[j].strip() == link:
                    iguais += 1
                else:
                    trocar.append((j, link, f"{nome} S{t:02}E{e:02}"))
                continue
            entram += [linha_ep(nome, t, e, logo, grupo) + fim, link + fim]
        if entram:
            if k is None:
                no_fim += entram
            else:
                depois.setdefault(series[k]["ultima"], []).extend(entram)
        resumo.append(f"  {nome}: {len(entram) // 2} episódio(s) novo(s)" + (f"  [{grupo}]" if grupo else ""))

    print()
    print("\n".join(resumo))
    if iguais:
        print(f"  {iguais} episódio(s) já estavam na lista com o mesmo link (pulados).")
    if trocar:
        print(f"\n  {len(trocar)} episódio(s) já existem na lista com OUTRO link:")
        for _, _, rotulo in trocar[:20]:
            print("    " + rotulo)
        if len(trocar) > 20:
            print(f"    ... e mais {len(trocar) - 20}")
        if not sim("  Trocar pelo link novo?"):
            trocar = []
    total = sum(len(v) for v in depois.values()) // 2 + len(no_fim) // 2
    if not total and not trocar:
        raise SystemExit("\n  Nada novo para colocar na lista.")
    if not sim(f"\n  Colocar {total} episódio(s)" + (f" e trocar {len(trocar)} link(s)" if trocar else "") + " na lista?"):
        raise SystemExit("  Nada foi mudado.")

    for j, link, _ in trocar:
        linhas[j] = link + fim
    saida = []
    for i, l in enumerate(linhas):
        saida.append(l)
        saida.extend(depois.get(i, ()))
    if no_fim:
        while saida and not saida[-1].strip():
            saida.pop()
        saida += no_fim + [""]

    bak = LISTA + ".bak"
    if os.path.exists(bak):
        os.remove(bak)
    os.replace(LISTA, bak)
    with open(LISTA, "w", encoding="utf-8", newline="") as f:
        f.write("\n".join(saida))
    # o que entrou fica anotado em episodios-adicionados.txt e o .txt volta a ficar vazio para a próxima vez
    with open(TXT, encoding="utf-8-sig", errors="replace") as f:
        feito = f.read()
    with open(FEITOS, "a", encoding="utf-8") as f:
        f.write(feito.rstrip() + "\n\n")
    with open(TXT, "w", encoding="utf-8-sig") as f:
        f.write(MODELO)
    print(f"\n  Pronto: {total} episódio(s) colocados na lista. A lista antiga ficou em lista.m3u.bak.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit("\n  Interrompido. Nada foi mudado.")
