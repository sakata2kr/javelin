const fs = require('fs');
const path = require('path');
const prompts = require('prompts');
const util = require('util');
const stream = require('stream');
const pipeline = util.promisify(stream.pipeline);
const tar = require('tar');
const zlib = require('zlib');
const AdmZip = require('adm-zip');
const { execSync } = require('child_process');
const readline = require('readline');

// fetch 옵션 설정
const options = {method: "GET", headers: { "Host" : "ccsapidev.sktelecom.com"}};

// 메인 함수
async function main()
{
    const baseDir = "C:\\projects";
    const baseRemoteUrl = "https://100.64.35.146/javelin";

    let baseFiles = [];
    let extensionCategory = [];
    let allFiles = [];
    let targetFiles = [];
    let targetExtensionCategory = [];

    let procDir = await getUserInput(`기본 디렉토리를 입력하세요 (기본값 : ${baseDir}) \n `);
    let existsDirs = [];

    procDir = procDir || baseDir;

    if ( !procDir.startsWith("C:\\") )
    {
        procDir = process.cwd() + "\\" + procDir;
    }

    const proceed = await getUserInput(`기본 설정 값은 ${procDir} 입니다.. 이대로 진행하겠습니까? (Y/N) `);
    if (/^[^Yy\n]/.test(proceed))
    {
        console.log('작업을 취소합니다.');
        return;
    }

    if (!fs.existsSync(procDir))
    {
        fs.mkdirSync(procDir);
    }
    else
    {
        existsDirs = getDirectoriesWithBin(procDir);
    }

    try
    {
        const files = await fs.promises.readdir(procDir);
        if (files.length != 0)
        {
            const proceed = await getUserInput(`${procDir} 는 현재 파일이나 하위폴더가 존재합니다.. 이대로 진행하겠습니까? (Y/N) `);
            if (/^[^Yy\n]/.test(proceed))
            {
                console.log(`${procDir} 폴더를 확인한 후 다시 진행해 주세요`);
                return;
            }
        }
    }
    catch (err)
    {
        console.error(`${procDir} 폴더를 확인할 수 없습니다.:`, err.message);
        return;
    }

    try
    {
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

    await getUserPrompt(true, '다운로드 및 설치할 대상 파일을 선택해 주세요', baseFiles.filter(file => !file.startsWith('microsoft-jdk')), targetFiles);
    await getUserPrompt(false, '설치할 JDK를 선택해 주세요\nJDK는 한건만 선택해 주세요', baseFiles.filter(file => file.startsWith('microsoft-jdk')), targetFiles);
    await getUserPrompt(true, 'VSCODE extensions 유형을 선택해 주세요', extensionCategory.filter(item => item != 'common'), targetExtensionCategory);

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
                        const writeStream = zlib.createGunzip(); // zlib 모듈을 사용하여 압축 해제

                        // 압축 해제한 데이터를 특정 폴더로 출력
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
                    console.log(`${file} 압축 해제 중 오류가 발생하여 Skip합니다.:`, error.message);
                }
            }
        }
    }

    // EXE 파일 존재 여부 확인
    tempFiles = targetFiles.filter(file => file.toLowerCase().includes(".exe"));

    if ( tempFiles.length != 0 )
    {
        for (const exeFile of tempFiles)
        {
            const proceed = await getUserInput(`${exeFile} 를 자동으로 설치할까요? (Y/N) `);
            if (/^[^Yy\n]/.test(proceed))
            {
                console.log(`${exeFile} 자동 설치는 Skip 합니다.`);
            }
            else
            {
                try
                {
                    const localFile = procDir + "\\" + exeFile;
                    if ( exeFile.toLowerCase().includes("vscode") )
                    {
                        console.log(`${exeFile} 를 자동으로 설치합니다. 설치 위치는 ${procDir}\\Microsoft VS Code 입니다.`);
                        execSync(`${localFile} /VERYSILENT /MERGETASKS=!runcode /Dir="${procDir}\\Microsoft VS Code"`, { encoding: 'utf8' });
                    }
                    else if ( exeFile.toLowerCase().includes("git") )
                    {
                        console.log(`${exeFile} 를 자동으로 설치합니다. 설치 위치는 ${procDir}\\git 입니다.`);
                        execSync(`${localFile} /VERYSILENT /NORESTART /NOCANCEL /SP- /CLOSEAPPLICATIONS /RESTARTAPPLICATIONS /COMPONENTS="icons,ext\reg\shellhere,assoc,assoc_sh" /Dir="${procDir}\\git"`, { encoding: 'utf8' });
                    }
                    else
                    {
                        console.log(`${exeFile}는 자동 설치를 지원하지 않는 exe 파일입니다. 수동으로 설치해 주세요`);
                    }

                }
                catch (error)
                {
                    console.error("오류가 발생하여 종료합니다.", error);
                    return;
                }
            }
        }

    }

    // VSCODE extensions 설치
    tempFiles = targetFiles.filter(file => file.toLowerCase().includes(".vsix"));

    if ( tempFiles.length != 0 )
    {
        const proceed = await getUserInput(`VSCODE Extensions을 자동으로 설치할까요? (Y/N) `);
        if (/^[^Yy\n]/.test(proceed))
        {
            console.log(`VSCODE Extensions 자동 설치는 Skip 합니다.`);
        }
        else
        {
            for (const vsixFile of tempFiles)
            {
                try
                {
                    const localFile = procDir + "\\" + vsixFile;
                    //console.log(`${vsixFile}를 자동으로 설치합니다.`);
                    execSync(`code --install-extension ${localFile}`, { stdio: 'ignore' });
                }
                catch (error)
                {
                    console.error(`${vsixFile} 설치 중 오류 발생으로 Skip 합니다.`, error.message);
                    return;
                }
            }
        }
    }

    // 레지스트리 등록
    const regTargets = getDirectoriesWithBin(procDir).filter(dir => !existsDirs.includes(dir));

    if ( regTargets.length != 0 )
    {
        const proceed = await getUserInput(`${regTargets}에 대한 레지스트리를 자동으로 등록할까요? (Y/N) `);
        if (/^[^Yy\n]/.test(proceed))
        {
            console.log(`${regTargets}에 대한 자동 레지스트리 등록은 Skip 합니다.`);
        }
        else
        {
            // 레지스트리 백업
            console.log(`기존 HKCU\\Environment 레지스트리에 대한 백업을 작성합니다.`);
            const date = new Date();
            const backupFileName = procDir + "\\" + `env_${date.getFullYear()}${date.getMonth()+1}${date.getDate()}.reg`;

            // 기존 레지스트리 파일이 존재하면 뒤에 .bak을 추가
            if (fs.existsSync(backupFileName))
            {
                fs.rename(backupFileName, backupFileName + '.bak');
            }
 
            execSync(`reg export HKCU\\Environment ${backupFileName}`, { encoding: 'utf8' }, { stdio: 'ignore' });
            console.log(`현재 레지스트리를 ${backupFileName} 파일로 백업하였습니다.`);

            let regPathValues = getRegistryValue('HKCU\\Environment', 'Path').split(";");

            for (const pathValue of regTargets )
            {
                if ( !regPathValues.includes(pathValue) )
                {
                    regPathValues.push(pathValue);
                }
            }

            // 공백 문자열 요소 제거
            regPathValues = regPathValues.filter(element => element.trim() !== "") // 공백 요소 제외
                                        .join(';') // ';'로 구분된 문자열로 구성
                                        + ';'; // 맨 뒤에 ';' 추가

            execSync(`setx Path "${regPathValues}"`, { encoding: 'utf8' }, { stdio: 'ignore' });
        }
    }

    await getUserInput("모든 설치 과정을 수행하였습니다.\n아무 키나 누르면 종료합니다.");
    process.exit(0);
}

// 사용자 입력을 받는 함수
function getUserInput(query)
{
    const rl = readline.createInterface({
        input: process.stdin,
        output: process.stdout
    });

    return new Promise(resolve =>
        rl.question(query, ans => {
            rl.close();
            resolve(ans);
        })
    );
}

// 프롬프트 처리
async function getUserPrompt(allowMultipleSelection, promptMessage, source, target)
{
    const response = await prompts({
        type: 'multiselect',
        name: 'values',
        message: promptMessage,
        choices: source.map(item => ({ title: item, value: item })),
        instructions: false,  // 설명을 숨깁니다.
    });

    if ( !allowMultipleSelection && response.values.length > 1 )
    {
        console.log("여러 건을 동시에 설정할 수 없습니다.\n한건만 선택해 주세요");
        return getUserPrompt(allowMultipleSelection, promptMessage, source, target)
    }
    else
    {
        response.values.forEach(item => target.push(item));
    }
}

// 파일 다운로드
async function downloadFile(fileUrl, outputLocationPath)
{
    try
    {
        //console.log(fileUrl);
        // 파일이 이미 존재하면 삭제
        if (fs.existsSync(outputLocationPath))
        {
            fs.unlinkSync(outputLocationPath);
        }

        const response = await fetch(fileUrl, options);

        if (!response.ok) {
            throw new Error(`Failed to download file: ${fileUrl}`);
        }

        const writer = fs.createWriteStream(outputLocationPath);

        await pipeline(response.body, writer);

        return true;
    }
    catch (error)
    {
        throw error;
    }
}

// 하위 디렉토리 목록 추출
function getDirectoriesWithBin(targetPath)
{
    const subdirectories = fs.readdirSync(targetPath);
    const directoriesWithBin = [];
  
    for (const dir of subdirectories)
    {
        const fullPath = path.join(targetPath, dir);
        if (fs.statSync(fullPath).isDirectory() && fs.existsSync(path.join(fullPath, 'bin')))
        {
            directoriesWithBin.push(path.join(fullPath, 'bin'));
        }
    }
  
    return directoriesWithBin;
}

// 레지스트리 추출
function getRegistryValue(key, valueName)
{
    try
    {
        const result = execSync(`reg query "${key}" /v "${valueName}"`, { encoding: 'utf8' });
        const match = result.match(new RegExp(`${valueName}\\s+REG_(?:EXPAND_)?SZ\\s+(.+)`, 'i'));
        return match ? match[1] : null;
    }
    catch (error)
    {
        console.error(`Failed to query registry key "${key}"`, error);
        return null;
    }
}

main();