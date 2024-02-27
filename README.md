[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)<br>
![Edge](https://img.shields.io/badge/Edge-0078D7?style=for-the-badge&logo=Microsoft-edge&logoColor=white)
![NodeJS](https://img.shields.io/badge/node.js-6DA55F?style=for-the-badge&logo=node.js&logoColor=white)
![JavaScript](https://img.shields.io/badge/javascript-%23323330.svg?style=for-the-badge&logo=javascript&logoColor=%23F7DF1E)
<br>

# Java 개발환경 및 VSCODE 설치 한방팩

## Java 및 빌드 관련 프로그램과 VSCODE 및 확장 프로그램을 한번에 설치합니다.

## 수행 방법

## 1. javelin.exe 를 실행합니다.
```
기본 디렉토리를 입력하세요 (기본값 : C:\projects)
```

해당 문구에서 아무것도 입력하지 않으면 기본값인 C:\projects에
모든 파일을 다운받고 설치 및 레지스트리 등록을 진행합니다.

다른 폴더에 설치를 원하시면 설치를 할 폴더명을 절대경로로 입력하시면 됩니다.

### 2. 기본 설정 값 확인 후 다음 진행을 위하여 Y 또는 y 또는 엔터키를 입력합니다.
```
기본 설정 값은 C:/projects 입니다.. 이대로 진행하겠습니까? (Y/N)
```

향후 모든 Y/N 구분은 Y 또는 y 또는 엔터키를 입력하면 다음으로 진행합니다.
다음으로 진행을 원하지 않으시면 다른 값을 입력하시면 됩니다.

### 3. 다운로드할 대상 파일을 선택합니다.
```
? 다운로드 및 설치할 대상 파일을 선택해 주세요 ›
◯   apache-maven-3.9.6-bin.tar.gz
◯   gradle-8.6-bin.zip
◯   Git-2.44.0-64-bit.exe
◯   VSCodeSetup-x64-1.86.2.exe
```
해당 다운로드 파일을 여러건 선택 후 처리가 가능합니다.
선택은 스페이스 또는 화살표 좌/우를 사용하여 선택합니다.
엔터키는 다음으로 진행하는 키이므로 아무것도 선택하지 않고 엔터키 입력 시
다음으로 진행합니다.

### 4. 설치할 JDK를 선택합니다.
```
? 설치할 JDK를 선택해 주세요
JDK는 한건만 선택해 주세요 ›
◯   microsoft-jdk-11.0.22-windows-x64.zip
◯   microsoft-jdk-17.0.10-windows-x64.zip
◯   microsoft-jdk-21.0.2-windows-x64.zip
```
역시 다운로드할 JDK 파일을 선택합니다.
이전 선택과 달리 여러건 선택은 불가합니다.

### 5. VSCODE extensions 유형을 선택합니다.
```
? VSCODE extensions 유형을 선택해 주세요 ›
◯   java
◯   python
◯   remote
◯   spring
```
VSCODE extensions 유형별로 구분되어 있어 모든 extensions 파일을 알 필요 없이 구성하였습니다.
설치할 카테고리 지정 시 카테고리 내 설정에 따라 관련 extensions 파일을 자동으로 다운로드 받고 설치까지 가능합니다.

### 6. 이후 과정은 모두 Y/N 선택으로 진행합니다.
마지막 진행 과정에서 기존 레지스트리에 대한 백업을 수행하고 레지스트리 등록 작업을 수행합니다.
기본적으로 레지스트리 백업은 기본 폴더 아래 env_년월일.reg로 백업됩니다.

만약 설치 중 오류가 발생하여 레지스트리에 대한 초기화가 필요할 경우
해당 파일을 참조하여 기존 키값을 수동으로 삭제한 후 해당 백업파일을 등록하면
원복이 가능합니다.
