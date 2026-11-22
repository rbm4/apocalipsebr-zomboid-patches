"""Run the manager's Umbrella health migration tests in a disposable PostgreSQL cluster."""
from pathlib import Path
import os, socket, subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
PG=Path(os.environ.get('APOCBR_TEST_POSTGRES_BIN','Z:/postgresql/bin'))
MANAGER=ROOT.parent/'apocalipsebr-manager/manager'
def run(args,**kwargs):subprocess.run([str(x) for x in args],check=True,**kwargs)
def main():
    with tempfile.TemporaryDirectory(prefix='npc_umbrella_health_') as folder:
        work=Path(folder).resolve()
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        run([PG/'initdb.exe','-D',work/'data','-U','postgres','-A','trust','--no-locale','-E','UTF8'],stdout=subprocess.DEVNULL)
        try:
            run([PG/'pg_ctl.exe','-D',work/'data','-l',work/'postgres.log','-o',f'-h 127.0.0.1 -p {port}','-w','start'],stdout=subprocess.DEVNULL)
            run([PG/'psql.exe','-h','127.0.0.1','-p',port,'-U','postgres','-d','postgres','-v','ON_ERROR_STOP=1','-c','CREATE ROLE apocalipsebr LOGIN; CREATE ROLE pz_server_01 LOGIN;'],stdout=subprocess.DEVNULL)
            env=dict(os.environ,JAVA_HOME=str(ROOT/'jdk'),GAME_RUNTIME_TEST_POSTGRES_URL=f'jdbc:postgresql://127.0.0.1:{port}/postgres')
            run([MANAGER/'mvnw.cmd','-o','-Dtest=NpcUmbrellaHealthMigrationTest','test'],cwd=MANAGER,env=env)
        finally:
            run([PG/'pg_ctl.exe','-D',work/'data','-m','immediate','-w','stop'],stdout=subprocess.DEVNULL)
if __name__=='__main__':main()
