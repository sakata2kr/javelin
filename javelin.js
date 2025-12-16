const fs = require('fs');
const path = require('path');
const util = require('util');
const stream = require('stream');
const pipeline = util.promisify(stream.pipeline);
const tar = require('tar');
const zlib = require('zlib');
const AdmZip = require('adm-zip');
const { execSync } = require('child_process');
const readline = require('readline');

// 사용자 입력 받기 함수
function getUserInput(question) {
    const rl = readline.createInterface({
        input: process.stdin,
        output: process.stdout
    });

    return new Promise((resolve) => {
        rl.question(question, (answer) => {
            rl.close();
            resolve(answer);
        });
    });
}

// 커맨드 라인 인자 파싱
const args = process.argv.slice(2);
const params = {
    url: null,
    clean: false,
    jdk25: false,
    maven: false,
    skipSts: false
};

args.forEach(arg => {
    if (arg.startsWith('--url=')) {
        params.url = arg.split('=')[1];
    } else if (arg === '--clean') {
        params.clean = true;
    } else if (arg === '--jdk25') {
        params.jdk25 = true;
    } else if (arg === '--maven') {
        params.maven = true;
    } else if (arg === '--skipSts') {
        params.skipSts = true;
    }
});

// fetch 옵션 설정
const options = {method: "GET", headers: { "Host" : "ccsapidev.sktelecom.com"}};

// 설치 여부 확인 함수
function checkInstalled(command) {
    try {
        const result = execSync(command, { encoding: 'utf8', stdio: ['pipe', 'pipe', 'ignore'] });
        return result.trim();
    } catch (error) {
        return null;
    }
}

// 설치된 프로그램 확인
function checkExistingInstallations() {
    const installations = {
        java: checkInstalled('java --version'),
        git: checkInstalled('git.exe --version'),
        maven: checkInstalled('mvn.exe --version'),
        gradle: checkInstalled('gradle.exe --version'),
        vscode: checkInstalled('code.exe --version')
    };

    return installations;
}

// STS 설치 여부 확인
function checkStsInstalled(baseDir) {
    try {
        const dirs = fs.readdirSync(baseDir);
        const stsDir = dirs.find(dir => dir.match(/^sts-\d+\.\d+\.\d+\.RELEASE$/));
        return stsDir ? path.join(baseDir, stsDir) : null;
    } catch (error) {
        return null;
    }
}

// 기본 디렉토리 결정
function determineBaseDirectory() {
    const cProjects = 'C:\\projects';
    const dProjects = 'D:\\projects';
    
    const cExists = fs.existsSync(cProjects);
    const dExists = fs.existsSync(dProjects);
    
    // 두개 모두 존재하는 경우 D:\projects 우선
    if (cExists && dExists) {
        return dProjects;
    }
    
    // D만 존재
    if (dExists) {
        return dProjects;
    }
    
    // C만 존재
    if (cExists) {
        return cProjects;
    }
    
    // 둘 다 없는 경우 D: 드라이브 확인
    try {
        const drives = execSync('wmic logicaldisk get name', { encoding: 'utf8' });
        const hasDDrive = drives.includes('D:');
        
        if (hasDDrive) {
            return dProjects;
        }
    } catch (error) {
        console.log('드라이브 확인 중 오류 발생, C 드라이브를 사용합니다.');
    }
    
    return cProjects;
}

// 설치된 프로그램 목록 조회
function getInstalledPrograms() {
    try {
        const result = execSync('wmic product get name,version', { encoding: 'utf8' });
        return result;
    } catch (error) {
        console.log('설치된 프로그램 목록 조회 실패:', error.message);
        return '';
    }
}

// 프로그램 제거 (Windows Installer 사용)
function uninstallProgram(programName) {
    try {
        console.log(`${programName} 제거 시도 중...`);
        execSync(`wmic product where "name like '%${programName}%'" call uninstall /nointeractive`, { 
            encoding: 'utf8',
            stdio: 'inherit'
        });
        console.log(`${programName} 제거 완료`);
        return true;
    } catch (error) {
        console.log(`${programName} 제거 실패:`, error.message);
        return false;
    }
}

// 프로그램 삭제 함수
async function cleanInstallation(procDir) {
    console.log('\n=== 기존 설치 프로그램 정리 시작 ===\n');
    
    const installations = checkExistingInstallations();
    const installedPrograms = getInstalledPrograms();
    
    // JDK 삭제 (Amazon Corretto JDK)
    if (installations.java) {
        console.log('기존 JDK 발견. 제거를 진행합니다...');
        
        // Amazon Corretto JDK 제거 시도
        if (installedPrograms.includes('Amazon Corretto')) {
            uninstallProgram('Amazon Corretto');
        }
        
        // Microsoft OpenJDK 제거 시도 (기존 설치가 있을 수 있음)
        if (installedPrograms.includes('Microsoft Build of OpenJDK')) {
            uninstallProgram('Microsoft Build of OpenJDK');
        }
        
        // Oracle JDK 제거 시도 (기존 설치가 있을 수 있음)
        if (installedPrograms.includes('Java') || installedPrograms.includes('JDK')) {
            uninstallProgram('Java');
        }
        
        // download 디렉토리에서 Amazon Corretto 설치 파일 실행하여 제거
        try {
            const downloadDir = path.join(procDir, 'download');
            if (fs.existsSync(downloadDir)) {
                const correttoFiles = fs.readdirSync(downloadDir).filter(file => 
                    file.includes('amazon-corretto') && file.endsWith('.msi')
                );
                
                for (const file of correttoFiles) {
                    const filePath = path.join(downloadDir, file);
                    console.log(`Amazon Corretto MSI를 사용하여 제거 시도: ${file}`);
                    try {
                        // MSI 파일을 사용하여 제거 (/x = uninstall, /quiet = 조용한 제거)
                        execSync(`msiexec /x "${filePath}" /quiet`, { 
                            encoding: 'utf8',
                            stdio: 'inherit',
                            timeout: 60000  // 60초 타임아웃
                        });
                        console.log(`${file}을 사용한 제거 완료`);
                    } catch (error) {
                        console.log(`${file}을 사용한 제거 실패:`, error.message);
                    }
                }
            }
        } catch (error) {
            console.log('Amazon Corretto MSI 제거 중 오류:', error.message);
        }
        
        // 포터블 버전 디렉토리 삭제
        try {
            const jdkDirs = fs.readdirSync(procDir).filter(dir => 
                dir.startsWith('jdk-') || 
                dir.startsWith('microsoft-jdk-') || 
                dir.startsWith('openjdk-') ||
                dir.startsWith('amazon-corretto-')
            );
            for (const dir of jdkDirs) {
                const fullPath = path.join(procDir, dir);
                console.log(`포터블 JDK 디렉토리 삭제: ${fullPath}`);
                fs.rmSync(fullPath, { recursive: true, force: true });
            }
        } catch (error) {
            console.log('JDK 디렉토리 삭제 중 오류:', error.message);
        }
    }
    
    // Git 삭제 (설치형 프로그램)
    if (installations.git) {
        console.log('기존 Git 발견. 제거를 진행합니다...');
        
        // Git for Windows 제거 시도
        if (installedPrograms.includes('Git')) {
            uninstallProgram('Git');
        }
        
        // download 디렉토리에서 Git 설치 파일 실행하여 제거
        try {
            const downloadDir = path.join(procDir, 'download');
            if (fs.existsSync(downloadDir)) {
                const gitFiles = fs.readdirSync(downloadDir).filter(file => 
                    file.toLowerCase().includes('git') && file.endsWith('.exe')
                );
                
                for (const file of gitFiles) {
                    const filePath = path.join(downloadDir, file);
                    console.log(`Git 설치 파일을 사용하여 제거 시도: ${file}`);
                    try {
                        // Git 설치 파일을 사용하여 제거 (/SILENT = 조용한 제거, /UNINSTALL = 제거)
                        execSync(`"${filePath}" /SILENT /UNINSTALL`, { 
                            encoding: 'utf8',
                            stdio: 'inherit',
                            timeout: 60000  // 60초 타임아웃
                        });
                        console.log(`${file}을 사용한 제거 완료`);
                    } catch (error) {
                        console.log(`${file}을 사용한 제거 실패:`, error.message);
                    }
                }
            }
        } catch (error) {
            console.log('Git 설치 파일 제거 중 오류:', error.message);
        }
        
        // 포터블 버전 디렉토리 삭제
        const gitPath = path.join(procDir, 'git');
        if (fs.existsSync(gitPath)) {
            console.log(`포터블 Git 디렉토리 삭제: ${gitPath}`);
            fs.rmSync(gitPath, { recursive: true, force: true });
        }
    }
    
    // VSCode 삭제 (설치형 프로그램)
    if (installations.vscode) {
        console.log('기존 VSCode 발견. 제거를 진행합니다...');
        
        // Visual Studio Code 제거 시도
        if (installedPrograms.includes('Microsoft Visual Studio Code')) {
            uninstallProgram('Microsoft Visual Studio Code');
        }
        
        // download 디렉토리에서 VSCode 설치 파일 실행하여 제거
        try {
            const downloadDir = path.join(procDir, 'download');
            if (fs.existsSync(downloadDir)) {
                const vscodeFiles = fs.readdirSync(downloadDir).filter(file => 
                    (file.toLowerCase().includes('vscode') || file.toLowerCase().includes('code')) && 
                    file.endsWith('.exe')
                );
                
                for (const file of vscodeFiles) {
                    const filePath = path.join(downloadDir, file);
                    console.log(`VSCode 설치 파일을 사용하여 제거 시도: ${file}`);
                    try {
                        // VSCode 설치 파일을 사용하여 제거 (/SILENT = 조용한 제거, /UNINSTALL = 제거)
                        execSync(`"${filePath}" /SILENT /UNINSTALL`, { 
                            encoding: 'utf8',
                            stdio: 'inherit',
                            timeout: 60000  // 60초 타임아웃
                        });
                        console.log(`${file}을 사용한 제거 완료`);
                    } catch (error) {
                        console.log(`${file}을 사용한 제거 실패:`, error.message);
                    }
                }
            }
        } catch (error) {
            console.log('VSCode 설치 파일 제거 중 오류:', error.message);
        }
        
        // 포터블 버전 디렉토리 삭제
        const vscodePath = path.join(procDir, 'Microsoft VS Code');
        if (fs.existsSync(vscodePath)) {
            console.log(`포터블 VSCode 디렉토리 삭제: ${vscodePath}`);
            fs.rmSync(vscodePath, { recursive: true, force: true });
        }
        
        // 사용자 데이터 디렉토리도 삭제할지 확인
        const userDataPath = path.join(process.env.APPDATA, 'Code');
        if (fs.existsSync(userDataPath)) {
            const proceed = await getUserInput('VSCode 사용자 데이터도 삭제하시겠습니까? (Y/N) ');
            if (/^[Yy\n]/.test(proceed)) {
                console.log(`VSCode 사용자 데이터 삭제: ${userDataPath}`);
                fs.rmSync(userDataPath, { recursive: true, force: true });
            }
        }
    }
    
    // Maven 삭제
    if (installations.maven) {
        console.log('기존 Maven 발견. 삭제를 진행합니다...');
        const mavenDirs = fs.readdirSync(procDir).filter(dir => dir.startsWith('apache-maven-'));
        for (const dir of mavenDirs) {
            const fullPath = path.join(procDir, dir);
            console.log(`${fullPath} 삭제 중...`);
            fs.rmSync(fullPath, { recursive: true, force: true });
        }
    }
    
    // Gradle 삭제
    if (installations.gradle) {
        console.log('기존 Gradle 발견. 삭제를 진행합니다...');
        const gradleDirs = fs.readdirSync(procDir).filter(dir => dir.startsWith('gradle-'));
        for (const dir of gradleDirs) {
            const fullPath = path.join(procDir, dir);
            console.log(`${fullPath} 삭제 중...`);
            fs.rmSync(fullPath, { recursive: true, force: true });
        }
    }
    
    // STS 삭제
    const stsPath = checkStsInstalled(procDir);
    if (stsPath) {
        console.log(`기존 STS 발견: ${stsPath}. 삭제를 진행합니다...`);
        fs.rmSync(stsPath, { recursive: true, force: true });
    }
    
    // 환경변수에서 삭제된 경로 제거
    console.log('\n환경변수 정리 중...');
    try {
        // 현재 PATH 환경변수 조회
        const currentPath = execSync('echo %PATH%', { encoding: 'utf8' }).trim();
        let pathValues = currentPath.split(';');
        
        // 제거할 경로 패턴들
        const pathPatternsToRemove = [
            'jdk', 'java', 'openjdk', 'microsoft-jdk',  // JDK 관련
            'git',                                       // Git 관련
            'Microsoft VS Code', 'Code',                 // VSCode 관련
            'apache-maven', 'maven',                     // Maven 관련
            'gradle'                                     // Gradle 관련
        ];
        
        // 제거할 경로 필터링
        const originalLength = pathValues.length;
        pathValues = pathValues.filter(pathEntry => {
            const shouldRemove = pathPatternsToRemove.some(pattern => 
                pathEntry.toLowerCase().includes(pattern.toLowerCase())
            );
            
            if (shouldRemove) {
                console.log(`환경변수에서 제거: ${pathEntry}`);
            }
            
            return !shouldRemove;
        });
        
        // 빈 항목 제거 및 PATH 재구성
        pathValues = pathValues.filter(entry => entry.trim() !== '');
        const newPath = pathValues.join(';');
        
        if (pathValues.length < originalLength) {
            // 사용자 환경변수 업데이트
            execSync(`setx PATH "${newPath}"`, { encoding: 'utf8' });
            console.log(`환경변수 정리 완료 (${originalLength - pathValues.length}개 항목 제거)`);
        } else {
            console.log('제거할 환경변수 항목이 없습니다.');
        }
    } catch (error) {
        console.log('환경변수 정리 중 오류 발생:', error.message);
    }
    
    console.log('\n=== 기존 설치 프로그램 정리 완료 ===\n');
}

// 메인 함수
async function main()
{
    console.log('=== 개발 환경 설치 스크립트 ===');
    console.log('파라미터:', params);
    console.log('');
    
    const baseDir = determineBaseDirectory();
    const baseRemoteUrl = params.url || "https://100.64.35.146/javelin";

    let baseFiles = [];
    let extensionCategory = [];
    let allFiles = [];
    let targetFiles = [];
    let targetExtensionCategory = [];

    let procDir = baseDir;
    let existsDirs = [];

    console.log(`기본 디렉토리: ${procDir}`);
    
    // 디렉토리가 없으면 생성
    if (!fs.existsSync(procDir)) {
        fs.mkdirSync(procDir, { recursive: true });
        console.log(`${procDir} 디렉토리를 생성했습니다.`);
    }
    
    // clean 파라미터가 있으면 기존 설치 삭제
    if (params.clean) {
        await cleanInstallation(procDir);
    }
    
    // 기존 설치 확인
    const installations = checkExistingInstallations();
    console.log('\n=== 기존 설치 확인 ===');
    console.log('Java:', installations.java ? '설치됨' : '미설치');
    console.log('Git:', installations.git ? '설치됨' : '미설치');
    console.log('Maven:', installations.maven ? '설치됨' : '미설치');
    console.log('Gradle:', installations.gradle ? '설치됨' : '미설치');
    console.log('VSCode:', installations.vscode ? '설치됨' : '미설치');
    
    const stsPath = checkStsInstalled(procDir);
    console.log('STS:', stsPath ? `설치됨 (${stsPath})` : '미설치');
    console.log('');

    const proceed = await getUserInput(`${procDir}에 설치를 진행하겠습니까? (Y/N) `);
    if (/^[^Yy\n]/.test(proceed)) {
        console.log('작업을 취소합니다.');
        return;
    }

    existsDirs = getDirectoriesWithBin(procDir);

    try {
        console.log(`\n원격 서버 연결 중: ${baseRemoteUrl}`);
        const response = await fetch(baseRemoteUrl + "/getAll", options);
        const responseData = await response.json();

        const promises = responseData.map(item => {
            return new Promise((resolve) => {
                if (!item.startsWith('extensions')) {
                    baseFiles.push(item);
                } else {
                    extensionCategory.push(item.split('/')[1]);
                }

                allFiles.push(item);
                resolve();
            });
        });

        await Promise.all(promises);

        baseFiles.sort((a, b) => {
            if ( a[0].toUpperCase() < b[0].toUpperCase() )
            {
                return -1;
            }
            else if (a[0].toUpperCase() > b[0].toUpperCase())
            {
                return 1;
            }
            else if (  a[0] > b[0] )
            {
                return -1;
            }
            else if (  a[0] < b[0] )
            {
                return 1;
            }
            else if ( a > b)
            {
                return 1;
            }
            else if ( a < b)
            {
                return -1;
            }
            else
            {
                return 0;
            }
        });

        extensionCategory = [...new Set(extensionCategory)].sort();
    }
    catch (error)
    {
        console.log("오류가 발생하여 종료합니다.:", error.message);
        return;
    }

    // 파일 필터링 (설치 여부 및 파라미터에 따라)
    let availableFiles = baseFiles.filter(file => !file.startsWith('microsoft-jdk'));
    
    // 이미 설치된 항목 제외
    if (installations.git && !params.clean) {
        availableFiles = availableFiles.filter(file => !file.toLowerCase().includes('git'));
    }
    if (installations.gradle && !params.clean) {
        availableFiles = availableFiles.filter(file => !file.toLowerCase().includes('gradle'));
    }
    if (installations.vscode && !params.clean) {
        availableFiles = availableFiles.filter(file => !file.toLowerCase().includes('vscode'));
    }
    if (stsPath && !params.clean && !params.skipSts) {
        availableFiles = availableFiles.filter(file => !file.toLowerCase().includes('sts'));
    }
    
    // skipSts 파라미터가 있으면 STS 제외
    if (params.skipSts) {
        availableFiles = availableFiles.filter(file => !file.toLowerCase().includes('sts'));
    }
    
    // maven 파라미터가 없고 clean도 아니면 maven 제외
    if (!params.maven && !params.clean && installations.maven) {
        availableFiles = availableFiles.filter(file => !file.toLowerCase().includes('maven'));
    }

    // 자동으로 모든 파일 추가 (이미 설치된 것 제외)
    targetFiles.push(...availableFiles);
    
    // JDK 선택
    if (!installations.java || params.clean) {
        let jdkFiles = baseFiles.filter(file => file.startsWith('microsoft-jdk'));
        
        // 파라미터에 따라 JDK 버전 필터링
        if (!params.jdk25) {
            // jdk25 파라미터가 없으면 JDK 21만 선택
            jdkFiles = jdkFiles.filter(file => file.includes('-21.'));
        } else {
            // jdk25 파라미터가 있으면 JDK 25만 선택
            jdkFiles = jdkFiles.filter(file => file.includes('-25.'));
        }
        
        if (jdkFiles.length > 0) {
            targetFiles.push(jdkFiles[0]); // 첫 번째 JDK만 선택
            console.log(`JDK 선택: ${jdkFiles[0]}`);
        }
    } else {
        console.log('\nJava가 이미 설치되어 있어 JDK 설치를 건너뜁니다.');
    }
    
    // VSCODE extensions는 모두 설치
    const extensionsToInstall = extensionCategory.filter(item => item != 'common');
    if (extensionsToInstall.length > 0) {
        targetExtensionCategory.push(...extensionsToInstall);
    }

    if (targetExtensionCategory.length > 0)
    {
        // 기본 디렉토리 하위에 extensions 라는 디렉토리 생성
        if (!fs.existsSync(procDir + "\\extensions"))
        {
            fs.mkdirSync(procDir + "\\extensions");
        }

        targetExtensionCategory.push('common');
        targetExtensionCategory = [...new Set(targetExtensionCategory)];

        for (const item of targetExtensionCategory)
        {
            // extensions 하위 category 디렉토리가 없으면 생성
            if (!fs.existsSync(procDir + "\\extensions\\" + item))
            {
                fs.mkdirSync(procDir + "\\extensions\\" + item);
            }

            for (const file of allFiles.filter(file => file.startsWith("extensions/" + item)))
            {
                targetFiles.push(file);
            }
        }
    }

    // 파일 다운로드
    console.log('\n=== 파일 다운로드 시작 ===\n');
    for (const file of targetFiles)
    {
        try
        {
            const localFile = procDir + "\\" + file;
            await downloadFile(baseRemoteUrl + "/getFile/" + file, localFile);
            console.log(`${file} 다운로드 완료`);
        }
        catch (error)
        {
            console.log(`${file} 다운로드 중 오류가 발생하여 Skip 합니다. :`, error.message);
        }
    }

    // 압축 파일 압축 해제
    let tempFiles = targetFiles.filter(file => file.toLowerCase().includes(".gz") || file.toLowerCase().includes(".zip"));

    if ( tempFiles.length != 0 )
    {
        console.log('\n=== 압축 파일 해제 ===\n');
        for (const file of tempFiles)
        {
            const proceed = await getUserInput(`${file} 를 압축 해제할까요? (Y/N) `);
            if (/^[^Yy\n]/.test(proceed))
            {
                console.log(`${file} 압축 해제는 Skip 합니다.`);
            }
            else
            {
                try
                {
                    const localFile = procDir + "\\" + file;

                    if ( file.toLowerCase().includes(".gz") )
                    {
                        const readStream = fs.createReadStream(localFile);
                        const writeStream = zlib.createGunzip();

                        const extractStream = tar.extract({
                            cwd: procDir,
                            strip: 0,
                        });

                        readStream.pipe(writeStream).pipe(extractStream);

                        await new Promise((resolve, reject) => {
                            extractStream.on('finish', resolve);
                            extractStream.on('error', reject);
                        });
                    }
                    else
                    {
                        const zip = new AdmZip(localFile);
                        zip.extractAllTo(procDir, /*overwrite*/ true);
                    }

                    console.log(`${file} 압축 해제를 완료하였습니다.`);
                }
                catch (error)
                {
                    console.log(`${file} 압축
 해제 중 오류:`, error.message);
                }
            }
        }
    }

    console.log('\n=== 설치 완료 ===');
    console.log('모든 작업이 완료되었습니다.');
}

// 파일 다운로드 함수
async function downloadFile(url, localPath) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
    }
    
    const buffer = await response.arrayBuffer();
    const dir = path.dirname(localPath);
    
    if (!fs.existsSync(dir)) {
        fs.mkdirSync(dir, { recursive: true });
    }
    
    fs.writeFileSync(localPath, Buffer.from(buffer));
}

// 디렉토리에서 bin 폴더가 있는 디렉토리들 찾기
function getDirectoriesWithBin(baseDir) {
    try {
        const dirs = fs.readdirSync(baseDir, { withFileTypes: true });
        return dirs
            .filter(dirent => dirent.isDirectory())
            .map(dirent => dirent.name)
            .filter(name => {
                const binPath = path.join(baseDir, name, 'bin');
                return fs.existsSync(binPath);
            });
    } catch (error) {
        return [];
    }
}

// 메인 함수 실행
if (require.main === module) {
    main().catch(console.error);
}