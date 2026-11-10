#!/usr/bin/env node
/**
 * Builds src/assets/login/scene.svg, the illustrated scene of the sign-in page, from the generator of
 * the login wireframe (payroll-login-wireframe_1.html, "Illustrated scene v2"), unchanged except that
 * its text (the four people labels, the depot and organisation signs) is left out: the page draws
 * that text over the image in the user's language (LoginHero). The page shows the file as an image,
 * so its own styles and animations need no inline style on the page (CSP style-src 'self').
 * Run: node scripts/build-login-scene.mjs (output committed).
 */
import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

let o = '';
o += `<defs>
<radialGradient id="sun" cx="430" cy="120" r="170" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#FFF4D2"/><stop offset=".35" stop-color="#FDF0CF" stop-opacity=".7"/><stop offset="1" stop-color="#EAF6EE" stop-opacity="0"/></radialGradient>
<linearGradient id="skyG" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#EAF6F1" stop-opacity="0"/><stop offset="1" stop-color="#DCEFE2"/></linearGradient>
<linearGradient id="hillFar" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#D3EADA"/><stop offset="1" stop-color="#C4E2CC"/></linearGradient>
<linearGradient id="hillMid" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#B7DCA0"/><stop offset="1" stop-color="#9FCF85"/></linearGradient>
<linearGradient id="glass" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#D6E9F5"/><stop offset=".6" stop-color="#9DC2DD"/><stop offset="1" stop-color="#7FA9C9"/></linearGradient>
<linearGradient id="wall" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#F3F6F8"/><stop offset="1" stop-color="#DCE3EA"/></linearGradient>
<linearGradient id="roof" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#A8B4BF"/><stop offset="1" stop-color="#7E8C99"/></linearGradient>
<linearGradient id="field" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#A9D48A"/><stop offset="1" stop-color="#6FB05A"/></linearGradient>
<linearGradient id="water" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#E4F6FA" stop-opacity="0"/><stop offset=".5" stop-color="#E4F6FA" stop-opacity=".95"/><stop offset="1" stop-color="#E4F6FA" stop-opacity="0"/></linearGradient>
<linearGradient id="woodTop" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#D49A62"/><stop offset=".6" stop-color="#B77A46"/><stop offset="1" stop-color="#A46A3A"/></linearGradient>
<linearGradient id="woodEdge" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#8E5830"/><stop offset="1" stop-color="#5E3519"/></linearGradient>
<linearGradient id="sack" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#F1E6C8"/><stop offset="1" stop-color="#D8C79E"/></linearGradient>
<linearGradient id="pot" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#FFFFFF"/><stop offset="1" stop-color="#E1DCCF"/></linearGradient>
<radialGradient id="vign" cx="50%" cy="45%" r="70%"><stop offset=".6" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#16284A" stop-opacity=".12"/></radialGradient>
<pattern id="check" width="6" height="6" patternUnits="userSpaceOnUse"><rect width="6" height="6" fill="#2E8B57"/><path d="M0 3h6M3 0v6" stroke="#E8F5EC" stroke-width="1"/></pattern>
<pattern id="corr" width="6" height="10" patternUnits="userSpaceOnUse"><rect width="6" height="10" fill="url(#roof)"/><rect width="2" height="10" fill="#fff" opacity=".18"/></pattern>
</defs>`;
/* sky + sun light */
o += `<circle cx="430" cy="120" r="170" fill="url(#sun)"/>`;
/* clouds (drift) */
const cloud = (x, y, s) =>
  `<g transform="translate(${x} ${y}) scale(${s})" opacity=".9"><ellipse cx="0" cy="0" rx="26" ry="10" fill="#fff"/><ellipse cx="-14" cy="-6" rx="14" ry="10" fill="#fff"/><ellipse cx="10" cy="-9" rx="16" ry="12" fill="#fff"/></g>`;
o += `<g class="drift">${cloud(90, 40, 1)}${cloud(330, 24, 0.8)}</g><g class="drift" style="animation-duration:55s">${cloud(250, 62, 0.6)}</g>`;
/* birds */
o += `<g class="fly">${[
  [0, 0],
  [16, 6],
  [30, -2],
]
  .map(
    ([x, y]) =>
      `<path d="M${x} ${y + 40} q5 -5 10 0 q5 -5 10 0" stroke="#5B6B7A" stroke-width="1.4" fill="none"/>`,
  )
  .join('')}</g>`;
/* far + mid hills (atmospheric perspective) */
o += `<path d="M-20 205 C80 175 170 190 260 182 S440 168 520 182 S650 190 720 176 V300 H-20z" fill="url(#hillFar)"/>`;
o += `<path d="M-20 228 C120 205 230 222 330 214 S520 205 600 218 S690 222 720 214 V300 H-20z" fill="url(#hillMid)" opacity=".8"/>`;
/* distant tree line */
for (let x = -10; x < 720; x += 18) {
  const r = 6 + ((x * 7) % 5);
  o += `<circle cx="${x}" cy="${222 - ((x * 13) % 9)}" r="${r}" fill="${(x / 18) % 2 ? '#8DC27A' : '#7BB76A'}" opacity=".8"/>`;
}

/* FCI-style godown (warehouse) — centre */
o += `<g><rect x="168" y="150" width="270" height="120" fill="url(#wall)"/>
<path d="M156 152 L303 104 L450 152 Z" fill="url(#corr)"/><path d="M156 152 L303 104 L450 152" stroke="#6B7987" stroke-width="2" fill="none"/>
<rect x="160" y="150" width="286" height="6" fill="#6B7987" opacity=".6"/>
${[200, 240, 280, 320, 360, 400].map((x) => `<rect x="${x}" y="164" width="18" height="8" rx="1" fill="#9AA8B6"/><path d="M${x + 2} 166h14M${x + 2} 169h14" stroke="#7E8C99" stroke-width=".8"/>`).join('')}
<rect x="248" y="124" width="110" height="16" rx="3" fill="#D6A531"/>
${[190, 262, 334, 406].map((x) => `<rect x="${x - 18}" y="200" width="44" height="70" fill="#8796A4"/>${Array.from({ length: 9 }, (_, i) => `<path d="M${x - 18} ${204 + i * 7.5}h44" stroke="#6F7D8B" stroke-width="1"/>`).join('')}`).join('')}
</g>`;
/* stacked grain sacks pyramid beside the godown */
const sack = (x, y, w = 34, h = 20) =>
  `<g><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="9" fill="url(#sack)" stroke="#C7B488" stroke-width="1"/><path d="M${x + 5} ${y + 4} q${w / 2 - 5} 3 ${w - 10} 0" stroke="#BFAA78" stroke-width="1" stroke-dasharray="2 2" fill="none"/><rect x="${x + w / 2 - 6}" y="${y + 7}" width="12" height="6" rx="1" fill="#17693A" opacity=".55"/></g>`;
for (let r = 0; r < 6; r++) {
  const n = 6 - r;
  for (let i = 0; i < n; i++) o += sack(440 + r * 17 + i * 32, 252 - r * 18);
}

/* corporate HQ — right, with reflections */
o += `<g><rect x="498" y="44" width="160" height="226" rx="3" fill="#E9EFF4"/><rect x="498" y="44" width="160" height="12" fill="#CBD6E0"/>`;
for (let r = 0; r < 10; r++)
  for (let k = 0; k < 6; k++)
    o += `<rect x="${506 + k * 25}" y="${64 + r * 19.5}" width="21" height="16" rx="1.5" fill="url(#glass)" opacity="${0.78 + ((r + k) % 3) * 0.07}"/>`;
o += `<path d="M520 64 L570 64 L520 150Z M590 64 L615 64 L560 160 L548 160Z" fill="#fff" opacity=".22"/>`;
o += `<rect x="520" y="50" width="116" height="13" rx="3" fill="#17693A"/>`;
o += `<rect x="652" y="118" width="46" height="152" rx="2" fill="#DDE5EC"/>`;
for (let r = 0; r < 7; r++)
  for (let k = 0; k < 2; k++)
    o += `<rect x="${656 + k * 20}" y="${126 + r * 20}" width="16" height="15" rx="1" fill="url(#glass)" opacity=".8"/>`;
o += `<path d="M556 236 h44 l6 -8 h-56z" fill="#16284A"/><rect x="560" y="236" width="36" height="34" fill="#2B4A75" opacity=".9"/><rect x="540" y="268" width="76" height="4" fill="#B9C4CF"/></g>`;
o += `<line x1="472" y1="270" x2="472" y2="104" stroke="#9AA7B4" stroke-width="2"/><g class="wave"><path d="M472 106 q12 -3 24 0 v16 q-12 -3 -24 0z" fill="#D6A531"/><path d="M472 111.3 q12 -3 24 0 v5.4 q-12 -3 -24 0z" fill="#fff"/><path d="M472 116.6 q12 -3 24 0 v5.4 q-12 -3 -24 0z" fill="#17693A"/></g>`;
[
  [452, 270, 15],
  [484, 272, 11],
  [690, 270, 13],
].forEach(
  ([x, y, r]) =>
    (o += `<circle cx="${x}" cy="${y - r}" r="${r}" fill="#5FA54F"/><circle cx="${x + r * 0.45}" cy="${y - r * 1.35}" r="${r * 0.7}" fill="#7DC06A"/><circle cx="${x - r * 0.3}" cy="${y - r * 1.2}" r="${r * 0.5}" fill="#9AD188" opacity=".8"/><rect x="${x - 1.5}" y="${y - 4}" width="3" height="8" fill="#7A5230"/>`),
);

/* rice paddy (terraced, water shimmer) */
o += `<path d="M-20 262 C150 252 300 258 460 255 S650 256 720 258 V330 H-20z" fill="url(#field)"/>`;
for (let r = 0; r < 7; r++) {
  const y = 266 + r * 9.5;
  o += `<g class="shim" style="animation-delay:${-r}s"><rect x="-20" y="${y + 3}" width="760" height="2.2" fill="url(#water)" opacity=".75"/></g>`;
  for (let x = -6 + (r % 2) * 6; x < 710; x += 13 - r) {
    const h = 5 + r * 1.4;
    o += `<path d="M${x} ${y + 5} l-${2 + r * 0.35} -${h} M${x} ${y + 5} l0 -${h + 1} M${x} ${y + 5} l${2 + r * 0.35} -${h}" stroke="${r % 2 ? '#4E9A3E' : '#3D8731'}" stroke-width="${1 + r * 0.13}" stroke-linecap="round"/>`;
  }
}

/* wheat helpers */
const ear = (px, py, deg, c) => {
  let t = '';
  for (let i = 0; i < 7; i++) {
    const k = i * 5.5,
      ex = px + Math.sin(deg / 57.3) * k,
      ey = py - Math.cos(deg / 57.3) * k;
    t += `<ellipse cx="${ex - 2.5}" cy="${ey}" rx="2.3" ry="4.4" fill="${c}" transform="rotate(${deg - 26} ${ex - 2.5} ${ey})"/><ellipse cx="${ex + 2.5}" cy="${ey}" rx="2.3" ry="4.4" fill="${c}" transform="rotate(${deg + 26} ${ex + 2.5} ${ey})"/><path d="M${ex - 3} ${ey - 3} l${-5 + deg / 10} -9 M${ex + 3} ${ey - 3} l${5 + deg / 10} -9" stroke="${c}" stroke-width=".7" opacity=".85"/>`;
  }
  return t;
};
const wheat = (x, yb, h, a, c, delay) => {
  const tx = x + Math.sin(a) * h * 0.62,
    ty = yb - Math.cos(a) * h * 0.62;
  return `<g class="sway" style="animation-delay:${delay}s"><path d="M${x} ${yb} Q${x + Math.sin(a) * h * 0.3} ${yb - h * 0.4} ${tx} ${ty}" stroke="${c}" stroke-width="1.7" fill="none"/><path d="M${x} ${yb - h * 0.25} q-10 -8 -14 -22" stroke="#9DB64A" stroke-width="1.6" fill="none"/>${ear(tx, ty, a * 57.3, c)}</g>`;
};
/* wheat field — left, tall, swaying */
[
  [2, 312, 170, -0.16],
  [14, 316, 186, -0.04],
  [26, 312, 176, 0.08],
  [40, 314, 160, 0.18],
  [60, 316, 190, 0.02],
  [76, 312, 168, 0.14],
  [96, 316, 150, -0.08],
  [118, 312, 158, 0.06],
  [134, 316, 140, 0.16],
  [150, 312, 130, -0.04],
].forEach(([x, y, h, a], i) => (o += wheat(x, y, h, a, i % 2 ? '#DDAE3C' : '#C99227', -(i * 0.37))));
/* rice plants with panicles beside laptop */
const rice = (x, yb) => {
  let t = `<g class="sway" style="animation-delay:-1.2s">`;
  for (let i = -3; i <= 3; i++)
    t += `<path d="M${x} ${yb} Q${x + i * 6} ${yb - 40} ${x + i * 14} ${yb - 70 + Math.abs(i) * 8}" stroke="${i % 2 ? '#4E9A3E' : '#5FAE4A'}" stroke-width="2.4" fill="none" stroke-linecap="round"/>`;
  t += `<path d="M${x} ${yb} Q${x + 4} ${yb - 84} ${x + 28} ${yb - 64}" stroke="#7FA83A" stroke-width="1.6" fill="none"/>`;
  for (let i = 0; i < 9; i++) {
    const u = i / 9;
    t += `<ellipse cx="${x + 10 + u * 18}" cy="${yb - 80 + u * 18}" rx="1.9" ry="3.1" fill="#D8C35A" transform="rotate(${30 + u * 40} ${x + 10 + u * 18} ${yb - 80 + u * 18})"/>`;
  }
  return t + '</g>';
};
o += rice(158, 324) + rice(548, 324);

/* ===== people (detailed, smiling) — drawn at origin: shoulder line y=0 ===== */
const face = (
  sk,
) => `<rect x="-7" y="-18" width="14" height="17" rx="5" fill="${sk}"/><path d="M-7 -6 q7 5 14 0" fill="#000" opacity=".12"/>
<circle cx="-21" cy="-35" r="4.2" fill="${sk}"/><circle cx="21" cy="-35" r="4.2" fill="${sk}"/><circle cx="0" cy="-36" r="21" fill="${sk}"/>
<path d="M9 -55 A21 21 0 0 1 9 -17 A24 24 0 0 0 9 -55Z" fill="#000" opacity=".07"/>
<path d="M-13 -46 q5 -3 9 -1 M4 -47 q5 -2 9 1" stroke="#3A2A1E" stroke-width="1.8" fill="none" stroke-linecap="round"/>
<path d="M-11 -39.5 q4 -4 8 0 M3 -39.5 q4 -4 8 0" stroke="#2A1E14" stroke-width="2.2" fill="none" stroke-linecap="round"/>
<path d="M-1 -36 q-2 5 1 6" stroke="#000" stroke-width="1" opacity=".18" fill="none"/>
<circle cx="-12" cy="-30" r="3.8" fill="#F08A7E" opacity=".35"/><circle cx="12" cy="-30" r="3.8" fill="#F08A7E" opacity=".35"/>
<path d="M-9.5 -28 Q0 -15.5 9.5 -28 Z" fill="#8E3B2F"/><path d="M-7.5 -27.6 Q0 -24 7.5 -27.6 Z" fill="#fff"/><path d="M-4 -20 q4 2 8 0" stroke="#E57F6F" stroke-width="1.6" fill="none" opacity=".6"/>`;
const torso = (c, shade = 0.13) =>
  `<path d="M-40 92 L-40 22 Q-40 0 -18 -4 L18 -4 Q40 0 40 22 L40 92Z" fill="${c}"/><path d="M14 -4 Q40 0 40 22 L40 92 L24 92 Q30 40 14 -4Z" fill="#000" opacity="${shade}"/><path d="M-35 22 Q-33 6 -20 1" stroke="#fff" stroke-width="2.2" opacity=".28" fill="none" stroke-linecap="round"/>`;
const armUp = (sleeve, sk, side = 1) =>
  `<path d="M${30 * side} 8 Q${54 * side} -6 ${48 * side} -34" stroke="${sleeve}" stroke-width="14" fill="none" stroke-linecap="round"/><circle cx="${48 * side}" cy="-42" r="8" fill="${sk}"/><path d="M${44 * side} -50 v-6 M${48 * side} -51 v-7 M${52 * side} -50 v-6" stroke="${sk}" stroke-width="3.6" stroke-linecap="round"/>`;
const armFront = (sleeve, sk, side = -1) =>
  `<path d="M${32 * side} 10 Q${40 * side} 42 ${8 * side} 50" stroke="${sleeve}" stroke-width="13" fill="none" stroke-linecap="round"/><circle cx="${6 * side}" cy="50" r="7" fill="${sk}"/>`;
const SK = ['#EBB993', '#C98A5F', '#B57650', '#DCA67C', '#E3AF88', '#BF8059'];
const PPL = {
  farmerMan: (sk) =>
    torso('#F6F2E8') +
    `<path d="M-30 2 L-8 92 L6 92 L-14 -2Z" fill="url(#check)"/>` +
    armUp('#F6F2E8', sk, 1) +
    `<g class="sway" style="animation-delay:-.6s">${ear(48, -58, 8, '#D9A93A')}${ear(42, -56, -12, '#C99227')}</g>` +
    face(sk) +
    `<path d="M-23 -44 Q-25 -70 0 -70 Q25 -70 23 -44 Q0 -53 -23 -44Z" fill="#F08C2E"/><path d="M-22 -50 Q0 -61 22 -50" stroke="#D66F14" stroke-width="2.4" fill="none"/><path d="M-21 -57 Q0 -67 21 -57" stroke="#D66F14" stroke-width="2" fill="none"/><path d="M18 -60 q12 2 14 18" stroke="#F08C2E" stroke-width="6" fill="none" stroke-linecap="round"/>` +
    `<path d="M-11 -31.5 q5.5 3.5 11 0 q5.5 3.5 11 0" stroke="#3A2A1E" stroke-width="3.2" fill="none" stroke-linecap="round"/>`,
  farmerWoman: (sk) =>
    `<path d="M-30 -40 Q-30 -72 0 -72 Q30 -72 30 -40 L40 30 L-40 30Z" fill="#2E8B57"/>` +
    torso('#2E8B57') +
    `<path d="M-40 30 L30 -2 L40 8 L-34 44Z" fill="#D6A531" opacity=".9"/>` +
    `<g transform="rotate(-28 0 40)">${Array.from({ length: 9 }, (_, i) => `<path d="M${-30 + i * 2} 70 L${18 + i * 2} 10" stroke="#C99227" stroke-width="1.5"/>`).join('')}<g class="sway">${ear(22, 6, 10, '#D9A93A')}${ear(28, 10, 30, '#DDAE3C')}${ear(16, 8, -8, '#C99227')}</g></g>` +
    armFront('#2E8B57', sk, -1) +
    face(sk) +
    `<path d="M-21 -46 Q0 -62 21 -46 Q12 -54 0 -54 Q-12 -54 -21 -46Z" fill="#2B1D16"/><path d="M-26 -44 Q0 -70 26 -44" stroke="#2E8B57" stroke-width="7" fill="none" stroke-linecap="round"/><path d="M-26 -44 Q0 -70 26 -44" stroke="#D6A531" stroke-width="1.6" fill="none"/>` +
    `<circle cx="0" cy="-46" r="2" fill="#C0392B"/><circle cx="-4" cy="-33" r="1.3" fill="#E5B53A"/><circle cx="-22" cy="-30" r="2.2" fill="#E5B53A"/><circle cx="22" cy="-30" r="2.2" fill="#E5B53A"/>`,
  labourer: (sk) =>
    torso('#B39462') +
    `<path d="M-8 -4 L0 18 L8 -4" fill="#8F7448"/><path d="M-40 40 h80" stroke="#8F7448" stroke-width="2" opacity=".5"/>` +
    `<g transform="rotate(-14 30 -16)"><rect x="4" y="-36" width="58" height="36" rx="14" fill="url(#sack)" stroke="#C7B488"/><path d="M10 -28 q24 4 48 0 M10 -10 q24 4 48 0" stroke="#BFAA78" stroke-width="1.2" stroke-dasharray="3 2" fill="none"/><rect x="24" y="-24" width="18" height="9" rx="1.5" fill="#17693A" opacity=".55"/></g>` +
    armUp(sk, sk, 1) +
    armFront(sk, sk, -1) +
    face(sk) +
    `<path d="M-21 -46 Q0 -64 21 -46 Q12 -55 0 -55 Q-12 -55 -21 -46Z" fill="#241811"/><path d="M-23 -46 h46" stroke="#C0392B" stroke-width="5" stroke-linecap="round"/><path d="M-22 -46 q-10 4 -8 14" stroke="#C0392B" stroke-width="4" fill="none" stroke-linecap="round"/>` +
    `<path d="M-13 -24 q13 8 26 0" stroke="#3A2A1E" stroke-width="1" opacity=".35" fill="none" stroke-dasharray="1 2"/>`,
  managerMan: (sk) =>
    torso('#1E3560', 0.18) +
    `<path d="M-12 -4 L0 30 L12 -4Z" fill="#fff"/><path d="M-3 2 L3 2 L4 28 L0 34 L-4 28Z" fill="#17693A"/><path d="M-12 -4 L-4 44 L-22 8Z M12 -4 L4 44 L22 8Z" fill="#16284A"/><rect x="-30" y="22" width="10" height="3" rx="1" fill="#fff" opacity=".8"/>` +
    armUp('#1E3560', sk, 1) +
    face(sk) +
    `<path d="M-21 -40 Q-24 -64 0 -63 Q24 -64 21 -42 Q14 -54 -2 -52 Q-14 -52 -21 -40Z" fill="#555"/><path d="M-14 -57 q8 -6 20 -3" stroke="#B5B5B5" stroke-width="1.8" fill="none"/>` +
    `<rect x="-15" y="-44" width="12" height="9" rx="3" fill="none" stroke="#16284A" stroke-width="1.5"/><rect x="3" y="-44" width="12" height="9" rx="3" fill="none" stroke="#16284A" stroke-width="1.5"/><path d="M-3 -40h6" stroke="#16284A" stroke-width="1.5"/>`,
  employeeWoman: (sk) =>
    `<path d="M-24 -30 Q-28 -64 0 -62 Q28 -64 24 -30 Q22 -12 12 -8 L16 -34 Q0 -46 -16 -34 L-12 -8 Q-22 -12 -24 -30Z" fill="#2B1D16"/>` +
    torso('#2F8F83') +
    `<path d="M-12 -4 L0 12 L12 -4" fill="#F3D9C4"/><path d="M-10 -2 L-2 34 M10 -2 L2 34" stroke="#16284A" stroke-width="2"/><rect x="-8" y="32" width="16" height="20" rx="2" fill="#fff"/><rect x="-5" y="35" width="10" height="6" rx="1" fill="#17693A"/>` +
    `<g><rect x="-34" y="24" width="30" height="40" rx="4" fill="#16284A"/><rect x="-31" y="27" width="24" height="30" rx="2" fill="#9DC2DD"/><path d="M-28 33 h14 M-28 38 h18 M-28 43 h10" stroke="#fff" stroke-width="1.6"/></g>` +
    armFront('#2F8F83', sk, -1) +
    face(sk) +
    `<path d="M-20 -40 Q-16 -60 0 -59 Q16 -60 20 -40 Q6 -52 -20 -40Z" fill="#2B1D16"/><circle cx="0" cy="-63" r="8" fill="#2B1D16"/><circle cx="0" cy="-50" r="1.6" fill="#C0392B"/>` +
    `<circle cx="-7" cy="-39" r="6" fill="none" stroke="#16284A" stroke-width="1.4"/><circle cx="7" cy="-39" r="6" fill="none" stroke="#16284A" stroke-width="1.4"/><path d="M-1 -39h2" stroke="#16284A" stroke-width="1.4"/><circle cx="-22" cy="-28" r="2" fill="#E5B53A"/><circle cx="22" cy="-28" r="2" fill="#E5B53A"/>`,
  employeeMan: (sk) =>
    torso('#9CC3E6') +
    `<path d="M-11 -4 L0 10 L11 -4" fill="#fff"/><path d="M-10 -2 L-2 30 M10 -2 L2 30" stroke="#17693A" stroke-width="2"/><rect x="-8" y="28" width="16" height="20" rx="2" fill="#fff"/><rect x="-5" y="31" width="10" height="6" rx="1" fill="#17693A"/><path d="M-26 40 v40 M26 40 v40" stroke="#7FA8CC" stroke-width="1"/>` +
    `<path d="M30 8 Q50 0 46 -24" stroke="#9CC3E6" stroke-width="13" fill="none" stroke-linecap="round"/><circle cx="46" cy="-30" r="7.5" fill="${sk}"/><path d="M46 -36 v-9" stroke="${sk}" stroke-width="4.5" stroke-linecap="round"/>` +
    face(sk) +
    `<path d="M-21 -40 Q-23 -63 0 -63 Q23 -63 21 -42 Q16 -52 6 -52 Q-6 -56 -21 -40Z" fill="#1F1712"/><path d="M-9 -58 q10 -4 18 0" stroke="#4A3A30" stroke-width="1.6" fill="none"/>`,
};
const person = (type, x, y, s, sk) =>
  `<g transform="translate(${x} ${y}) scale(${s})"><ellipse cx="0" cy="92" rx="46" ry="6" fill="#000" opacity=".08"/>${PPL[type](sk)}<path d="M38 22 Q40 50 40 92" stroke="#FFF4D2" stroke-width="2" opacity=".35" fill="none"/></g>`;
/* back row */
o += person('farmerWoman', 30, 250, 0.84, SK[3]) + person('farmerMan', 116, 248, 0.84, SK[2]);
o += person('employeeWoman', 590, 248, 0.84, SK[4]) + person('employeeMan', 672, 250, 0.84, SK[5]);
/* front row */
o += person('labourer', 72, 292, 1, SK[1]) + person('managerMan', 630, 292, 1, SK[0]);
/* labels */

/* ===== wooden workstation ===== */
o += `<path d="M-20 336 L720 336 L720 398 L-20 398Z" fill="url(#woodTop)"/>`;
for (let i = 0; i < 10; i++) {
  const y = 340 + i * 6;
  o += `<path d="M-20 ${y} C140 ${y - 3} 300 ${y + 3} 450 ${y} S640 ${y - 2} 720 ${y + 1}" stroke="#A9703F" stroke-width="${i % 3 ? 0.8 : 1.4}" fill="none" opacity=".5"/>`;
}
o += `<ellipse cx="210" cy="360" rx="14" ry="3.5" fill="none" stroke="#9A6436" stroke-width="1" opacity=".55"/><ellipse cx="210" cy="360" rx="7" ry="1.8" fill="none" stroke="#9A6436" stroke-width="1" opacity=".55"/>`;
o += `<rect x="-20" y="336" width="740" height="10" fill="#fff" opacity=".08"/><rect x="-20" y="398" width="740" height="32" fill="url(#woodEdge)"/><rect x="-20" y="396" width="740" height="3" fill="#E1AE78" opacity=".75"/>`;
o += `<ellipse cx="350" cy="384" rx="240" ry="9" fill="#4A2810" opacity=".25"/>`;
/* potted plant (detailed leaves) — left */
o += `<ellipse cx="50" cy="386" rx="32" ry="5" fill="#4A2810" opacity=".25"/><path d="M28 338 L72 338 L66 386 L34 386Z" fill="url(#pot)"/><rect x="25" y="331" width="50" height="9" rx="3" fill="#ECE7DB"/><path d="M30 352 h40" stroke="#D6A531" stroke-width="2" opacity=".7"/>`;
o += `<g class="sway" style="animation-duration:6s">`;
[
  [-70, 52],
  [-110, 50],
  [-90, 62],
  [-40, 42],
  [-140, 42],
  [-58, 64],
  [-122, 60],
  [-80, 46],
  [-100, 40],
].forEach(([a, l], i) => {
  const r = (a * Math.PI) / 180,
    x = 50,
    y = 332,
    ex = x + Math.cos(r) * l,
    ey = y + Math.sin(r) * l,
    mx = (x + ex) / 2,
    my = (y + ey) / 2,
    nx = -Math.sin(r) * 10,
    ny = Math.cos(r) * 10;
  o += `<path d="M${x} ${y} Q${mx + nx} ${my + ny} ${ex} ${ey} Q${mx - nx} ${my - ny} ${x} ${y}Z" fill="${['#3F9B4F', '#56B35E', '#2F8A43'][i % 3]}"/><path d="M${x} ${y} Q${mx} ${my} ${ex} ${ey}" stroke="#E6F5E2" stroke-width=".8" opacity=".6" fill="none"/>`;
});
o += `</g>`;
/* glass jar with wheat bouquet — right of laptop */
o += `<g><ellipse cx="560" cy="388" rx="16" ry="3.5" fill="#4A2810" opacity=".2"/><path d="M548 352 h24 v32 q0 4 -4 4 h-16 q-4 0 -4 -4z" fill="#DDEFF3" opacity=".55" stroke="#C3DDE3"/><path d="M551 370 h18" stroke="#9FC7D1" stroke-width="1" opacity=".7"/>
<g class="sway" style="animation-delay:-2s">${[-0.35, -0.15, 0, 0.18, 0.35]
  .map((a, i) => {
    const h = 58 + (i % 2) * 8,
      tx = 560 + Math.sin(a) * h,
      ty = 380 - Math.cos(a) * h;
    return `<path d="M560 380 L${tx} ${ty}" stroke="#C99227" stroke-width="1.4"/>${ear(tx, ty, a * 57.3, i % 2 ? '#DDAE3C' : '#C99227')}`;
  })
  .join('')}</g></g>`;
/* mug with animated steam */
o += `<g><ellipse cx="610" cy="389" rx="22" ry="4" fill="#4A2810" opacity=".22"/><rect x="592" y="350" width="34" height="38" rx="6" fill="#FBFBF8"/><rect x="592" y="350" width="10" height="38" rx="5" fill="#fff"/><rect x="618" y="352" width="8" height="34" rx="4" fill="#000" opacity=".06"/><path d="M626 358 q14 0 14 10 q0 10 -14 10" stroke="#F1F1EC" stroke-width="5" fill="none"/><ellipse cx="609" cy="351" rx="17" ry="3" fill="#6B3F20"/>
<path d="M609 362 q-6 8 0 16 q6 -8 0 -16" fill="#17693A"/><path d="M609 364 v12" stroke="#fff" stroke-width=".8" opacity=".7"/>
${[0, 1, 2].map((i) => `<path class="steam" style="animation-delay:${i * 0.9}s" d="M${602 + i * 7} 344 q4 -6 0 -12 q-4 -6 0 -12" stroke="#C8D2CC" stroke-width="2" fill="none" stroke-linecap="round"/>`).join('')}</g>`;
/* notebook, pen, phone */
o += `<path d="M470 390 L556 386 L562 398 L476 402Z" fill="#16284A"/><path d="M474 388 L554 384" stroke="#fff" stroke-width="1" opacity=".5"/><path d="M486 395 L548 383" stroke="#D6A531" stroke-width="3" stroke-linecap="round"/><path d="M548 383 l4 -1" stroke="#16284A" stroke-width="3" stroke-linecap="round"/>`;
o += `<rect x="650" y="378" width="38" height="18" rx="4" fill="#1E2430" transform="rotate(-6 669 387)"/><rect x="653" y="380.5" width="32" height="13" rx="2" fill="#2E3A4F" transform="rotate(-6 669 387)"/>`;
/* wheat + rice sprigs lying on the desk */
const lying = (x, y, l, a, c) => {
  let t = `<path d="M${x} ${y} l${l} ${a}" stroke="${c}" stroke-width="1.6"/>`;
  for (let i = 0; i < 7; i++) {
    const px = x + l * (0.58 + i * 0.06),
      py = y + a * (0.58 + i * 0.06);
    t += `<ellipse cx="${px}" cy="${py - 2.4}" rx="4.4" ry="2.2" fill="${c}" transform="rotate(-24 ${px} ${py - 2.4})"/><ellipse cx="${px}" cy="${py + 2.4}" rx="4.4" ry="2.2" fill="${c}" transform="rotate(24 ${px} ${py + 2.4})"/><path d="M${px} ${py - 3} l8 -6 M${px} ${py + 3} l8 6" stroke="${c}" stroke-width=".7"/>`;
  }
  return t;
};
o +=
  `<ellipse cx="160" cy="398" rx="70" ry="3" fill="#4A2810" opacity=".18"/>` +
  lying(96, 392, 124, -6, '#D9A93A') +
  lying(104, 396, 112, 2, '#C99227');
o += `<path d="M380 393 Q420 385 462 392" stroke="#6E9A3A" stroke-width="1.6" fill="none"/>`;
for (let i = 0; i < 10; i++) {
  const u = i / 10;
  o += `<ellipse cx="${410 + u * 46}" cy="${390 + Math.sin(u * 3.1) * 3}" rx="3.2" ry="1.7" fill="#D8C35A" transform="rotate(${-10 + u * 30} ${410 + u * 46} 390)"/>`;
}
/* soft lighting: warm key light + vignette */

const css = `.sway{transform-box:fill-box;transform-origin:50% 100%;animation:sway 4.5s ease-in-out infinite alternate}
@keyframes sway{from{transform:rotate(-2.5deg)}to{transform:rotate(2.5deg)}}
.steam{animation:steam 2.8s ease-in-out infinite;opacity:0}
@keyframes steam{0%{opacity:0;transform:translateY(6px)}40%{opacity:.85}100%{opacity:0;transform:translateY(-14px)}}
.drift{animation:drift 40s ease-in-out infinite alternate}@keyframes drift{to{transform:translateX(70px)}}
.shim{animation:shim 5s ease-in-out infinite alternate}@keyframes shim{to{transform:translateX(-16px)}}
.fly{animation:fly 24s linear infinite}@keyframes fly{from{transform:translate(-80px,20px)}to{transform:translate(780px,-20px)}}
.wave{transform-box:fill-box;transform-origin:0 50%;animation:flag 2.4s ease-in-out infinite alternate}@keyframes flag{to{transform:skewY(-4deg) scaleX(.94)}}
@media (prefers-reduced-motion:reduce){*{animation:none!important}.steam{opacity:0}}`;
const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 700 430" preserveAspectRatio="xMidYMax meet" overflow="visible"><style>${css}</style>${o}</svg>
`;
writeFileSync(resolve(root, 'src/assets/login/scene.svg'), svg);
console.log(`build-login-scene: ${svg.length} bytes -> src/assets/login/scene.svg`);
