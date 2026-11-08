// Waystation gates with Butler: walk a bot through a station gate and back. Uses the local Waystation build.
// Run with: node waystation.mjs 26.1.2 | 26.2 | 26.3
import path from 'node:path'
import { readdirSync } from 'node:fs'
import { JARS, PackHost, makePack, freshServer, Server, Bot, sleep, until, check, section, summary, butlerId, walkTo } from './harness.mjs'
import { removeServer } from './harness.mjs'

const VERSION = process.argv[2] ?? '26.3'
const JAVA = process.env.RPB_TEST_JAVA ?? 'java' // Java 25
if (VERSION === '26.3') process.env.RPB_TEST_STILL = '1' // see Bot: 26.3 kicks bots that move through ViaBackwards
const jar = n => path.join(JARS, n)
const plugins = [jar('multiverse-core-5.8.2.jar'), jar('multiverse-inventories-5.3.6.jar'), (() => { const d = path.join(process.env.HOME, 'Code/waystation/build/libs'); const f = readdirSync(d).filter(n => /^Waystation-[\d.]+\.jar$/.test(n)); if (f.length !== 1) throw new Error('expected one Waystation jar in ' + d); return path.join(d, f[0]) })()]
plugins.push(jar('ViaVersion-5.12.3-SNAPSHOT.jar'), jar('ViaBackwards-5.12.3-SNAPSHOT.jar'))
const dirs = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] }

const host = new PackHost(); await host.up()
const one = makePack('one'), two = makePack('two')
host.put(`/b/${one.sha1}.zip`, one.zip); host.put(`/b/${two.sha1}.zip`, two.zip)
const dir = freshServer(`waystation-${VERSION}`, { paper: jar(`paper-${VERSION}.jar`), plugins, butlerConfig: `packs:
  one: {url: "${host.url(`/b/${one.sha1}.zip`)}"}
  two: {url: "${host.url(`/b/${two.sha1}.zip`)}"}
worlds:
  world: [one]
  world_nether: [one]
  world_the_end: [one]
  waystation: []
  alpha: [two]
` })
const s = new Server({ dir, java: JAVA })
process.on('exit', () => { if (s.proc && s.proc.exitCode === null) s.proc.kill('SIGKILL') })

section(`Waystation gates on Paper ${VERSION}`)
await s.start(); await sleep(5000)
check('control: Waystation is enabled', s.log.some(l => /Enabling Waystation/.test(l)) && !s.log.some(l => /Error occurred while enabling Waystation/.test(l)))
await s.ask('mv create alpha normal --world-type FLAT --no-structures', 8000)
await s.ask('difficulty peaceful', 500)
await s.ask('gate reload', 4000)
const gates = {}
for (const l of await s.ask('gate list', 1500)) {
  const x = l.match(/((?:station|return)-\S+): (\S+) (-?\d+) (-?\d+) (-?\d+) (\w+)/)
  if (x) gates[x[1]] = { world: x[2], x: +x[3], y: +x[4], z: +x[5], f: x[6] }
}
check('control: alpha got a station gate and a return gate', gates['station-alpha'] && gates['return-alpha'], JSON.stringify(gates))
const walkInto = async (b, g) => {
  const [dx, dz] = dirs[g.f]
  await s.ask(`execute in ${g.world} run tp ${b.username} ${g.x + .5 + dx * 3} ${g.y} ${g.z + .5 + dz * 3}`, 2500)
  await walkTo(b.bot, g.x + .5, g.z + .5, 6000); await sleep(2500)
}
const ID = { one: butlerId('one'), two: butlerId('two') }
const butler = b => b.stack.filter(p => Object.values(ID).includes(p.uuid))
const has = (b, name) => until(() => { const st = butler(b); return name ? st.length === 1 && st[0].uuid === ID[name] && st[0].state === 'loaded' : st.length === 0 }, 8000)

const w = new Bot('Walker'); await w.join(); await s.ask('op Walker', 400)
check('joining the main world gets its pack', await has(w, 'one'))
await s.ask('mvtp Walker w:waystation', 2500)
check('the station world, listed as [], takes Butler\'s pack off', await has(w, null))
if (process.env.RPB_TEST_STILL === '1') {
  console.log('  NOT TESTED on this version: walking through a gate needs a bot that moves, and 26.3 kicks moving bots behind ViaBackwards')
} else if (gates['station-alpha']) {
  await walkInto(w, gates['station-alpha'])
  const inAlpha = (await s.ask('execute in alpha if entity @a[name=Walker,distance=0..]', 600)).some(l => /Test passed/.test(l))
  check('control: walking through the gate reaches alpha', inAlpha)
  check('a gate trip gets the destination\'s pack', await has(w, 'two'))
  await walkInto(w, gates['return-alpha'])
  const back = (await s.ask('execute in waystation if entity @a[name=Walker,distance=0..]', 600)).some(l => /Test passed/.test(l))
  check('control: the return gate leads back to the station', back)
  check('and the pack follows', await has(w, null))
}
check('the walker was never kicked', !w.ended, w.kicked)
check('no exceptions from Butler', s.exceptions().length === 0, s.exceptions().slice(0, 3).join('\n'))
await w.quit(); await s.stop(); await host.down()
removeServer(dir)
process.exit(summary() ? 1 : 0)
