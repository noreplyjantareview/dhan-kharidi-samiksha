const fs = require('node:fs');
const path = require('node:path');
const { PGlite } = require('@electric-sql/pglite');
const { pgcrypto } = require('@electric-sql/pglite/contrib/pgcrypto');
const root = path.resolve(__dirname, '..');
(async () => {
  const db = new PGlite({ extensions: { pgcrypto } });
  try {
    await db.exec(`CREATE ROLE anon; CREATE ROLE authenticated;
      CREATE SCHEMA auth; CREATE TABLE auth.users(id uuid PRIMARY KEY);
      CREATE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$
        SELECT nullif(current_setting('request.jwt.claim.sub', true), '')::uuid $$;
      GRANT USAGE ON SCHEMA auth TO anon, authenticated;
      GRANT EXECUTE ON FUNCTION auth.uid() TO anon, authenticated;`);
    for (const name of ['schema.sql', 'admin_dashboard.sql', 'kendra_edit.sql', 'duty_flow.sql', 'face_template_v2.sql', 'strict_monitor.sql', 'shifts_nodal.sql', 'bulk_kendras.sql', 'face_auth_v3.sql']) {
      await db.exec(fs.readFileSync(path.join(root, name), 'utf8'));
      console.log('Applied local migration:', name);
    }
    const migration = fs.readFileSync(path.join(root, 'face_auth_v3.sql'), 'utf8');
    await db.exec(migration);
    await db.exec(fs.readFileSync(path.join(__dirname, 'face_auth_v3_tests.sql'), 'utf8'));
    const count = await db.query('SELECT count(*)::int AS passed FROM test_results');
    console.log('Face authorization assertions passed:', count.rows[0].passed);
    await db.exec(migration);
    console.log('Empty and populated v3 reruns passed.');
  } finally {
    await db.close();
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
