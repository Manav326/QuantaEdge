import Link from 'next/link';

export default function LoginPage() {
  return <main className="auth-page"><div className="auth-brand"><Link href="/" className="brand"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link></div><section className="auth-card"><span className="eyebrow">Welcome back</span><h1>अपनी पढ़ाई वहीं से शुरू करें।</h1><p>यह local preview है। Production signup में real OTP, parent verification और consent के बाद ही student account activate होगा।</p><label>Mobile number<input placeholder="10 digit mobile number" inputMode="numeric" aria-label="10 digit mobile number" /></label><Link href="/student" className="button button-dark full">Local preview खोलें →</Link><small className="auth-note">Preview data केवल local Docker में seeded है; production compose preview seed बंद रखता है।</small><Link href="/student" className="text-link">Student preview खोलें →</Link></section></main>;
}
