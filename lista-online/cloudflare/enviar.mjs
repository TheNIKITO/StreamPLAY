// Comprime e envia a lista para o R2:  node enviar.mjs <pasta com lista.m3u> [prefixo no R2]
// O canais.m3u vai junto se existir; se não existir, sai do R2 (os canais somem do app, como no Firebase).
import { readFileSync, writeFileSync, existsSync, mkdtempSync, rmSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { spawnSync } from 'node:child_process'

const pasta = process.argv[2] || '../public'
const prefixo = process.argv[3] ? process.argv[3].replace(/\/?$/, '/') : ''
const BALDE = 'streamplay-lista'
const tmp = mkdtempSync(join(tmpdir(), 'streamplay-r2-'))

function wrangler(...args) {
  const r = spawnSync(['npx', 'wrangler', ...args].join(' '), { stdio: 'inherit', shell: true })
  return r.status === 0
}

try {
  for (const nome of ['lista.m3u', 'canais.m3u']) {
    const origem = join(pasta, nome)
    const chave = `${BALDE}/${prefixo}${nome}.gz`
    if (!existsSync(origem)) {
      if (nome === 'lista.m3u') throw new Error(`Não achei ${origem}`)
      wrangler('r2', 'object', 'delete', chave, '--remote')   // sem canais.m3u: tira do R2 (pode já não existir)
      continue
    }
    const gz = gzipSync(readFileSync(origem), { level: 9 })
    const arq = join(tmp, nome + '.gz')
    writeFileSync(arq, gz)
    console.log(`Enviando ${nome} (${(gz.length / 1e6).toFixed(1)} MB comprimido)...`)
    if (!wrangler('r2', 'object', 'put', chave, '--file', `"${arq}"`, '--content-type', 'application/gzip', '--remote'))
      throw new Error(`Falhou o envio de ${nome}`)
  }
} catch (e) {
  console.error(e.message)
  process.exitCode = 1
} finally {
  rmSync(tmp, { recursive: true, force: true })
}
