import { useEffect, useRef } from 'react';
import { useI18n } from '@/shared/i18n';
import scene from '@/assets/login/scene.svg';

/** The four things the wireframe names, each with its icon; the label breaks before its last word. */
const FEATURES = [
  { key: 'login.feature.employees', tone: 'lx-ft-employees', icon: <EmployeesIcon /> },
  { key: 'login.feature.payroll', tone: 'lx-ft-payroll', icon: <PayrollIcon /> },
  { key: 'login.feature.leave', tone: 'lx-ft-leave', icon: <LeaveIcon /> },
  { key: 'login.feature.reports', tone: 'lx-ft-reports', icon: <ReportsIcon /> },
] as const;

/** A label on two lines, as the wireframe sets it ("Employee / Management", "Reports & / Analytics"). */
function twoLines(label: string) {
  const at = label.lastIndexOf(' ');
  if (at < 0) return label;
  return (
    <>
      {label.slice(0, at)}
      <br />
      {label.slice(at + 1)}
    </>
  );
}

/**
 * The left half of the sign-in page, as the login wireframe (payroll-login-wireframe_1.html, owner
 * 2026-10-30: no deviation): the product name and the organisation, the headline, the lede, the four
 * features and the illustrated scene with the workspace on a laptop. The product is FCI Lekha, the
 * organisation the Food Corporation of India; the brand tile is the wireframe's own drawing, no
 * logo image (owner). The scene is the
 * wireframe's own drawing (scripts/build-login-scene.mjs); its words are drawn here, in the user's
 * language. Decorative except the text; no inline style (CSP style-src 'self').
 */
export function LoginHero() {
  const { t } = useI18n();
  return (
    <section className="lx-hero" aria-label={t('login.storyLabel')}>
      <div className="lx-brand lx-rv lx-d1">
        {/* The wireframe's brand tile: a building on a white tile (drawn, not an image file). */}
        <div className="lx-mark" aria-hidden="true">
          <svg viewBox="0 0 40 40" fill="none" stroke="#17693A" strokeWidth="2.6" strokeLinecap="round">
            <path d="M5 15 20 6l15 9" />
            <path d="M8 16h24M10 19v11M16 19v11M24 19v11M30 19v11M6 33h28" />
          </svg>
        </div>
        <div>
          <div className="lx-nm">
            {t('brand.fci')}
            <span> {t('brand.lekha')}</span>
          </div>
          <div className="lx-org">{t('brand.corporation')}</div>
        </div>
      </div>

      <h1 className="lx-rv lx-d2">
        {t('login.headline')}
        <span className="lx-sub">
          {t('login.headlineMade')} <em>{t('login.headlineEm')}</em>
        </span>
      </h1>
      <p className="lx-lede lx-rv lx-d3">{t('login.lede')}</p>

      <div className="lx-feats lx-rv lx-d4" role="list" aria-label={t('login.featuresLabel')}>
        {FEATURES.map((f) => (
          <div key={f.key} className="lx-ft" role="listitem">
            <i className={f.tone} aria-hidden="true">
              {f.icon}
            </i>
            {twoLines(t(f.key))}
          </div>
        ))}
      </div>

      <Scene />
    </section>
  );
}

/** The illustrated scene: the drawing, its words, and the workspace on a laptop (all decorative). */
function Scene() {
  const { t } = useI18n();
  const box = useRef<HTMLDivElement>(null);
  // The laptop is drawn at 560 px and scaled to 54 % of the scene, as the wireframe's fit(); a CSS
  // variable set through the CSSOM, which the CSP allows (no style attribute).
  useEffect(() => {
    const el = box.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const fit = () => {
      const w = el.clientWidth;
      if (w) el.style.setProperty('--lx-k', ((w * 0.54) / 560).toFixed(3));
    };
    fit();
    const watch = new ResizeObserver(fit);
    watch.observe(el);
    return () => watch.disconnect();
  }, []);
  return (
    <div ref={box} className="lx-scene lx-rv lx-d5" aria-hidden="true">
      {/* A plain <img>: next/image adds an inline style, which the CSP (style-src 'self') refuses. */}
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img className="lx-scene-art" src={scene.src} alt="" width={700} height={430} />
      <svg className="lx-scene-words" viewBox="0 0 700 430" preserveAspectRatio="xMidYMax meet">
        <text x="303" y="135.5" textAnchor="middle" className="lx-sign lx-sign-depot">
          {t('login.art.depot')}
        </text>
        <text x="578" y="59.6" textAnchor="middle" className="lx-sign lx-sign-org">
          {t('login.art.organisation')}
        </text>
        <Tag x={72} y={140} text={t('login.art.farmers')} tone="#D6A531" />
        <Tag x={104} y={306} text={t('login.art.labourers')} tone="#C0392B" />
        <Tag x={630} y={140} text={t('login.art.employees')} tone="#2F8F83" />
        <Tag x={628} y={312} text={t('login.art.management')} tone="#16284A" />
      </svg>
      <div className="lx-laptop">
        <div className="lx-screen">
          <div className="lx-app">
            <div className="lx-app-top">
              🏛 <b>{t('login.art.product')}</b> {t('login.system')}
              <span className="lx-app-user">{t('login.art.admin')} ▾</span>
            </div>
            <div className="lx-app-side">
              <span className="on">▣ {t('login.art.dashboard')}</span>
              <span>👤 {t('login.art.employee')}</span>
              <span>₹ {t('login.art.payroll')}</span>
              <span>🗓 {t('login.art.leave')}</span>
              <span>📄 {t('login.art.reports')}</span>
              <span>⚙ {t('login.art.masters')}</span>
              <span>🛡 {t('login.art.administration')}</span>
            </div>
            <div className="lx-app-main">
              <div className="lx-kpis">
                <Kpi tone="lx-k-blue" label={t('login.art.kpiEmployees')} value="1,248" />
                <Kpi tone="lx-k-green" label={t('login.art.kpiPayroll')} value="₹ 12.45 Cr" />
                <Kpi tone="lx-k-gold" label={t('login.art.kpiLeaves')} value="26" />
                <Kpi tone="lx-k-red" label={t('login.art.kpiPending')} value="12" />
              </div>
              <div className="lx-charts">
                <div className="lx-chart">
                  <h6>{t('login.art.trend')}</h6>
                  <div className="lx-bars">
                    {[1, 2, 3, 4, 5].map((n) => (
                      <div key={n}>
                        <i className={`lx-b${n}a`} />
                        <i className={`lx-b${n}b`} />
                      </div>
                    ))}
                  </div>
                </div>
                <div className="lx-chart">
                  <h6>{t('login.art.leaveSplit')}</h6>
                  <div className="lx-donut">
                    <svg viewBox="0 0 42 42">
                      <circle cx="21" cy="21" r="15.9" fill="none" stroke="#E6EBF1" strokeWidth="7" />
                      <circle
                        cx="21"
                        cy="21"
                        r="15.9"
                        fill="none"
                        stroke="#17693A"
                        strokeWidth="7"
                        strokeDasharray="40 60"
                        strokeDashoffset="25"
                      />
                      <circle
                        cx="21"
                        cy="21"
                        r="15.9"
                        fill="none"
                        stroke="#E3A92A"
                        strokeWidth="7"
                        strokeDasharray="22 78"
                        strokeDashoffset="-15"
                      />
                      <circle
                        cx="21"
                        cy="21"
                        r="15.9"
                        fill="none"
                        stroke="#16284A"
                        strokeWidth="7"
                        strokeDasharray="20 80"
                        strokeDashoffset="-37"
                      />
                      <circle
                        cx="21"
                        cy="21"
                        r="15.9"
                        fill="none"
                        stroke="#2F6FD6"
                        strokeWidth="7"
                        strokeDasharray="10 90"
                        strokeDashoffset="-57"
                      />
                    </svg>
                    <div className="lx-legend">
                      <span className="lx-l-earned">{t('login.art.earned')}</span>
                      <span className="lx-l-casual">{t('login.art.casual')}</span>
                      <span className="lx-l-medical">{t('login.art.medical')}</span>
                      <span className="lx-l-maternity">{t('login.art.maternity')}</span>
                      <span className="lx-l-others">{t('login.art.others')}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div className="lx-base" />
      </div>
    </div>
  );
}

/** A person's label in the scene (the wireframe's pill: a dot, then the word). */
function Tag({ x, y, text, tone }: { x: number; y: number; text: string; tone: string }) {
  const half = text.length * 3.3;
  return (
    <g>
      <rect
        x={x - half - 12}
        y={y}
        width={text.length * 6.6 + 22}
        height="20"
        rx="10"
        fill="#fff"
        opacity=".95"
        stroke={tone}
        strokeWidth="1.2"
      />
      <circle cx={x - half - 2} cy={y + 10} r="3" fill={tone} />
      <text x={x + 4} y={y + 13.8} textAnchor="middle" className="lx-tag">
        {text}
      </text>
    </g>
  );
}

function Kpi({ tone, label, value }: { tone: string; label: string; value: string }) {
  return (
    <div className="lx-kpi">
      <i className={tone} />
      <div>
        <small>{label}</small>
        <b>{value}</b>
      </div>
    </div>
  );
}

function EmployeesIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="#17693A">
      <circle cx="12" cy="7" r="3.2" />
      <circle cx="5.5" cy="9" r="2.4" />
      <circle cx="18.5" cy="9" r="2.4" />
      <path d="M6 20c0-3.5 2.7-6 6-6s6 2.5 6 6zM1 19c0-2.6 1.8-4.6 4.4-4.6 1 0 1.8.3 2.5.8C6.8 16.3 6 17.7 6 19.6zM23 19c0-2.6-1.8-4.6-4.4-4.6-1 0-1.8.3-2.5.8 1.1 1.1 1.9 2.5 1.9 4.4z" />
    </svg>
  );
}

function PayrollIcon() {
  return (
    <svg viewBox="0 0 24 24">
      <circle cx="12" cy="12" r="9" fill="#E3A92A" />
      <path
        d="M8.5 8h7M8.5 10.5h7M10 8c3 0 3.5 4.5 0 4.5H8.8l4.8 4.5"
        stroke="#5A3D00"
        strokeWidth="1.6"
        fill="none"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

function LeaveIcon() {
  return (
    <svg viewBox="0 0 24 24">
      <path d="M6 3h8l4 4v14H6z" fill="#2F6FD6" />
      <path d="M14 3v4h4" fill="#9DBEF2" />
      <path d="M9 12h6M9 15h6M9 18h4" stroke="#fff" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

function ReportsIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="#D94545">
      <rect x="4" y="13" width="4" height="7" rx="1" />
      <rect x="10" y="9" width="4" height="11" rx="1" />
      <rect x="16" y="5" width="4" height="15" rx="1" />
    </svg>
  );
}
