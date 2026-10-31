// Entrega a lista (lista.m3u, canais.m3u...) guardada no R2 já comprimida (arquivo.m3u.gz), como o Firebase fazia:
// com ETag/Last-Modified, e 304 quando o app já tem a versão atual (quase nada de download).
export default {
  async fetch(req, env) {
    if (req.method !== 'GET' && req.method !== 'HEAD') return new Response('Método não permitido', { status: 405 })
    const nome = decodeURIComponent(new URL(req.url).pathname.slice(1))
    if (!/^[\w-]+(\/[\w-]+)*\.m3u8?$/.test(nome)) return new Response('Não encontrado', { status: 404 })

    const info = await env.LISTA.head(nome + '.gz')
    if (!info) return new Response('Não encontrado', { status: 404 })
    if (naoMudou(req, info)) return new Response(null, { status: 304, headers: cabecalhos(info) })
    if (req.method === 'HEAD') return new Response(null, { headers: cabecalhos(info) })

    const obj = await env.LISTA.get(nome + '.gz')
    if (!obj) return new Response('Não encontrado', { status: 404 })
    const h = cabecalhos(obj)
    if (/\bgzip\b/.test(req.headers.get('Accept-Encoding') || '')) {
      h.set('Content-Encoding', 'gzip')
      h.set('Content-Length', String(obj.size))
      return new Response(obj.body, { headers: h, encodeBody: 'manual' })
    }
    return new Response(obj.body.pipeThrough(new DecompressionStream('gzip')), { headers: h })
  },
}

function cabecalhos(o) {
  return new Headers({
    'Content-Type': 'audio/x-mpegurl; charset=utf-8',
    'Cache-Control': 'no-cache',
    'ETag': o.httpEtag,
    'Last-Modified': o.uploaded.toUTCString(),
    'Vary': 'Accept-Encoding',
  })
}

function naoMudou(req, o) {
  const inm = req.headers.get('If-None-Match')
  if (inm) return inm.split(',').some(t => t.trim().replace(/^W\//, '') === o.httpEtag)
  const ims = Date.parse(req.headers.get('If-Modified-Since') || '')
  return !isNaN(ims) && Math.floor(o.uploaded.getTime() / 1000) * 1000 <= ims
}
