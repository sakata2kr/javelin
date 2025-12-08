package main

import (
	"archive/tar"
	"archive/zip"
	"bufio"
	"compress/gzip"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const (
	defaultURL = "https://ncs.nova.sktelecom.com"
)

type FileManager struct {
	baseURL                string
	baseFiles              []string
	extensionCategory      []string
	allFiles               []string
	targetFiles            []string
	targetExtensionCategory []string
	procDir                string
	existsDirs             []string
}

func main() {
	// 커맨드 라인 파라미터 정의
	urlFlag := flag.String("url", "", "서버 URL (기본값: https://ncs.nova.sktelecom.com)")
	flag.Parse()

	// URL 결정
	baseURL := defaultURL
	if *urlFlag != "" {
		baseURL = *urlFlag
		fmt.Printf("커맨드 라인에서 URL을 지정했습니다: %s\n", baseURL)
	} else {
		fmt.Printf("기본 URL을 사용합니다: %s\n", baseURL)
	}

	fm := &FileManager{
		baseURL: baseURL,
	}
	
	if err := fm.run(); err != nil {
		fmt.Printf("오류가 발생하여 종료합니다: %v\n", err)
		os.Exit(1)
	}
}

func (fm *FileManager) run() error {
	fmt.Printf("\n접속 URL: %s\n", fm.baseURL)

	// 디렉토리 자동 결정
	procDir, err := fm.determineProjectDir()
	if err != nil {
		return err
	}

	fm.procDir = procDir
	fmt.Printf("선택된 작업 디렉토리: %s\n", procDir)

	proceed, err := getUserInput(fmt.Sprintf("기본 설정 값은 %s 입니다.. 이대로 진행하겠습니까? (Y/N) ", procDir))
	if err != nil {
		return err
	}

	if !strings.HasPrefix(strings.ToLower(proceed), "y") && proceed != "" {
		fmt.Println("작업을 취소합니다.")
		return nil
	}

	// 디렉토리 생성 또는 확인
	if _, err := os.Stat(procDir); os.IsNotExist(err) {
		if err := os.MkdirAll(procDir, 0755); err != nil {
			return err
		}
	} else {
		fm.existsDirs = getDirectoriesWithBin(procDir)
		files, _ := os.ReadDir(procDir)
		if len(files) > 0 {
			proceed, err := getUserInput(fmt.Sprintf("%s 는 현재 파일이나 하위폴더가 존재합니다.. 이대로 진행하겠습니까? (Y/N) ", procDir))
			if err != nil {
				return err
			}
			if !strings.HasPrefix(strings.ToLower(proceed), "y") && proceed != "" {
				fmt.Printf("%s 폴더를 확인한 후 다시 진행해 주세요\n", procDir)
				return nil
			}
		}
	}

	// 원격 파일 목록 가져오기
	if err := fm.fetchRemoteFiles(); err != nil {
		return err
	}

	// 사용자 선택 받기 (자동)
	if err := fm.getUserSelections(); err != nil {
		return err
	}

	// 파일 다운로드
	if err := fm.downloadFiles(); err != nil {
		return err
	}

	// 압축 파일 처리
	if err := fm.extractArchives(); err != nil {
		return err
	}

	// EXE 파일 설치
	if err := fm.installExecutables(); err != nil {
		return err
	}

	// VSIX 확장 프로그램 설치
	if err := fm.installVSCodeExtensions(); err != nil {
		return err
	}

	// 레지스트리 등록
	if err := fm.registerPaths(); err != nil {
		return err
	}

	getUserInput("모든 설치 과정을 수행하였습니다.\n아무 키나 누르면 종료합니다.")
	return nil
}

func (fm *FileManager) determineProjectDir() (string, error) {
	cProjects := "C:\\projects"
	dProjects := "D:\\projects"

	cExists := dirExists(cProjects)
	dExists := dirExists(dProjects)

	// 1. 두 개 모두 존재하는 경우 D:\projects 선택
	if cExists && dExists {
		fmt.Println("C:\\projects와 D:\\projects가 모두 존재합니다.")
		fmt.Println("D:\\projects를 작업 디렉토리로 선택합니다.")
		return dProjects, nil
	}

	// 2. C:\projects만 존재하는 경우
	if cExists && !dExists {
		fmt.Println("C:\\projects가 존재합니다.")
		return cProjects, nil
	}

	// 3. D:\projects만 존재하는 경우
	if !cExists && dExists {
		fmt.Println("D:\\projects가 존재합니다.")
		return dProjects, nil
	}

	// 4. 두 개 모두 존재하지 않는 경우
	fmt.Println("C:\\projects와 D:\\projects가 모두 존재하지 않습니다.")
	
	// D 드라이브 존재 여부 확인
	if driveExists("D:") {
		fmt.Println("D: 드라이브가 존재합니다. D:\\projects를 생성합니다.")
		if err := os.MkdirAll(dProjects, 0755); err != nil {
			return "", fmt.Errorf("D:\\projects 생성 실패: %v", err)
		}
		fmt.Println("✓ D:\\projects 디렉토리를 생성했습니다.")
		return dProjects, nil
	}

	// D 드라이브가 없으면 C:\projects 생성
	fmt.Println("D: 드라이브가 존재하지 않습니다. C:\\projects를 생성합니다.")
	if err := os.MkdirAll(cProjects, 0755); err != nil {
		return "", fmt.Errorf("C:\\projects 생성 실패: %v", err)
	}
	fmt.Println("✓ C:\\projects 디렉토리를 생성했습니다.")
	return cProjects, nil
}

func (fm *FileManager) fetchRemoteFiles() error {
	client := &http.Client{}
	req, err := http.NewRequest("GET", fm.baseURL+"/getAll", nil)
	if err != nil {
		return err
	}

	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	var files []string
	if err := json.NewDecoder(resp.Body).Decode(&files); err != nil {
		return err
	}

	extensionCategoryMap := make(map[string]bool)
	
	for _, item := range files {
		fm.allFiles = append(fm.allFiles, item)
		if !strings.HasPrefix(item, "extensions") {
			fm.baseFiles = append(fm.baseFiles, item)
		} else {
			parts := strings.Split(item, "/")
			if len(parts) > 1 {
				extensionCategoryMap[parts[1]] = true
			}
		}
	}

	for category := range extensionCategoryMap {
		fm.extensionCategory = append(fm.extensionCategory, category)
	}

	sort.Strings(fm.extensionCategory)
	sort.Slice(fm.baseFiles, func(i, j int) bool {
		return strings.ToLower(fm.baseFiles[i]) < strings.ToLower(fm.baseFiles[j])
	})

	return nil
}

func (fm *FileManager) getUserSelections() error {
	// 모든 non-JDK 파일 자동 선택
	nonJDKFiles := filterFiles(fm.baseFiles, func(s string) bool {
		return !strings.HasPrefix(s, "microsoft-jdk")
	})
	
	fmt.Printf("\n다음 파일들을 자동으로 다운로드 및 설치합니다:\n")
	for _, file := range nonJDKFiles {
		fmt.Printf("  - %s\n", file)
	}
	fm.targetFiles = append(fm.targetFiles, nonJDKFiles...)

	// JDK 자동 선택 (정렬된 목록 중 첫 번째)
	jdkFiles := filterFiles(fm.baseFiles, func(s string) bool {
		return strings.HasPrefix(s, "microsoft-jdk")
	})
	
	if len(jdkFiles) > 0 {
		sort.Strings(jdkFiles)
		selectedJDK := jdkFiles[0]
		fmt.Printf("\nJDK는 %s 를 자동으로 설치합니다.\n", selectedJDK)
		fm.targetFiles = append(fm.targetFiles, selectedJDK)
	}

	// Extension 카테고리 전체 자동 선택
	nonCommonExt := filterFiles(fm.extensionCategory, func(s string) bool {
		return s != "common"
	})
	
	if len(nonCommonExt) > 0 {
		fmt.Printf("\n다음 VSCODE extensions 유형을 자동으로 설치합니다:\n")
		for _, ext := range nonCommonExt {
			fmt.Printf("  - %s\n", ext)
		}
		fm.targetExtensionCategory = nonCommonExt
	}

	// Extension 파일 추가
	if len(fm.targetExtensionCategory) > 0 {
		extDir := filepath.Join(fm.procDir, "extensions")
		if err := os.MkdirAll(extDir, 0755); err != nil {
			return err
		}

		fm.targetExtensionCategory = append(fm.targetExtensionCategory, "common")
		fm.targetExtensionCategory = uniqueStrings(fm.targetExtensionCategory)

		for _, category := range fm.targetExtensionCategory {
			categoryDir := filepath.Join(extDir, category)
			if err := os.MkdirAll(categoryDir, 0755); err != nil {
				return err
			}

			for _, file := range fm.allFiles {
				if strings.HasPrefix(file, "extensions/"+category) {
					fm.targetFiles = append(fm.targetFiles, file)
				}
			}
		}
	}

	return nil
}

func (fm *FileManager) downloadFiles() error {
	client := &http.Client{}
	
	fmt.Println("\n파일 다운로드를 시작합니다...")
	for i, file := range fm.targetFiles {
		fmt.Printf("[%d/%d] %s 다운로드 중...\n", i+1, len(fm.targetFiles), file)
		
		localFile := filepath.Join(fm.procDir, file)
		
		// 디렉토리 생성
		if err := os.MkdirAll(filepath.Dir(localFile), 0755); err != nil {
			fmt.Printf("%s 다운로드 중 오류가 발생하여 Skip 합니다: %v\n", file, err)
			continue
		}

		req, err := http.NewRequest("GET", fm.baseURL+"/getFile/"+file, nil)
		if err != nil {
			fmt.Printf("%s 다운로드 중 오류가 발생하여 Skip 합니다: %v\n", file, err)
			continue
		}

		resp, err := client.Do(req)
		if err != nil {
			fmt.Printf("%s 다운로드 중 오류가 발생하여 Skip 합니다: %v\n", file, err)
			continue
		}

		out, err := os.Create(localFile)
		if err != nil {
			resp.Body.Close()
			fmt.Printf("%s 다운로드 중 오류가 발생하여 Skip 합니다: %v\n", file, err)
			continue
		}

		_, err = io.Copy(out, resp.Body)
		out.Close()
		resp.Body.Close()

		if err != nil {
			fmt.Printf("%s 다운로드 중 오류가 발생하여 Skip 합니다: %v\n", file, err)
			continue
		}

		fmt.Printf("✓ %s 다운로드 완료\n", file)
	}

	return nil
}

func (fm *FileManager) extractArchives() error {
	archiveFiles := filterFiles(fm.targetFiles, func(s string) bool {
		lower := strings.ToLower(s)
		return strings.Contains(lower, ".gz") || strings.Contains(lower, ".zip")
	})

	if len(archiveFiles) == 0 {
		return nil
	}

	fmt.Println("\n압축 파일 처리를 시작합니다...")
	for _, file := range archiveFiles {
		proceed, err := getUserInput(fmt.Sprintf("%s 를 압축 해제할까요? (Y/N) ", file))
		if err != nil {
			return err
		}

		if !strings.HasPrefix(strings.ToLower(proceed), "y") && proceed != "" {
			fmt.Printf("%s 압축 해제는 Skip 합니다.\n", file)
			continue
		}

		localFile := filepath.Join(fm.procDir, file)

		if strings.Contains(strings.ToLower(file), ".gz") {
			if err := extractTarGz(localFile, fm.procDir); err != nil {
				fmt.Printf("%s 압축 해제 중 오류가 발생하여 Skip합니다: %v\n", file, err)
				continue
			}
		} else if strings.Contains(strings.ToLower(file), ".zip") {
			if err := extractZip(localFile, fm.procDir); err != nil {
				fmt.Printf("%s 압축 해제 중 오류가 발생하여 Skip합니다: %v\n", file, err)
				continue
			}
		}

		fmt.Printf("✓ %s 압축 해제를 완료하였습니다.\n", file)
	}

	return nil
}

func (fm *FileManager) installExecutables() error {
	exeFiles := filterFiles(fm.targetFiles, func(s string) bool {
		return strings.Contains(strings.ToLower(s), ".exe")
	})

	if len(exeFiles) == 0 {
		return nil
	}

	fmt.Println("\nEXE 파일 설치를 시작합니다...")
	for _, exeFile := range exeFiles {
		proceed, err := getUserInput(fmt.Sprintf("%s 를 자동으로 설치할까요? (Y/N) ", exeFile))
		if err != nil {
			return err
		}

		if !strings.HasPrefix(strings.ToLower(proceed), "y") && proceed != "" {
			fmt.Printf("%s 자동 설치는 Skip 합니다.\n", exeFile)
			continue
		}

		localFile := filepath.Join(fm.procDir, exeFile)
		lowerExe := strings.ToLower(exeFile)

		if strings.Contains(lowerExe, "vscode") {
			installDir := filepath.Join(fm.procDir, "Microsoft VS Code")
			fmt.Printf("%s 를 자동으로 설치합니다. 설치 위치는 %s 입니다.\n", exeFile, installDir)
			cmd := exec.Command(localFile, "/VERYSILENT", "/MERGETASKS=!runcode", fmt.Sprintf("/Dir=%s", installDir))
			if err := cmd.Run(); err != nil {
				fmt.Printf("%s 설치 중 오류 발생: %v\n", exeFile, err)
			} else {
				fmt.Printf("✓ %s 설치 완료\n", exeFile)
			}
		} else if strings.Contains(lowerExe, "git") {
			installDir := filepath.Join(fm.procDir, "git")
			fmt.Printf("%s 를 자동으로 설치합니다. 설치 위치는 %s 입니다.\n", exeFile, installDir)
			cmd := exec.Command(localFile, "/VERYSILENT", "/NORESTART", "/NOCANCEL", "/SP-", 
				"/CLOSEAPPLICATIONS", "/RESTARTAPPLICATIONS", 
				"/COMPONENTS=icons,ext\\reg\\shellhere,assoc,assoc_sh", 
				fmt.Sprintf("/Dir=%s", installDir))
			if err := cmd.Run(); err != nil {
				fmt.Printf("%s 설치 중 오류 발생: %v\n", exeFile, err)
			} else {
				fmt.Printf("✓ %s 설치 완료\n", exeFile)
			}
		} else {
			fmt.Printf("%s는 자동 설치를 지원하지 않는 exe 파일입니다. 수동으로 설치해 주세요\n", exeFile)
		}
	}

	return nil
}

func (fm *FileManager) installVSCodeExtensions() error {
	vsixFiles := filterFiles(fm.targetFiles, func(s string) bool {
		return strings.Contains(strings.ToLower(s), ".vsix")
	})

	if len(vsixFiles) == 0 {
		return nil
	}

	fmt.Println("\nVSCode Extensions 설치를 시작합니다...")
	proceed, err := getUserInput("VSCODE Extensions을 자동으로 설치할까요? (Y/N) ")
	if err != nil {
		return err
	}

	if !strings.HasPrefix(strings.ToLower(proceed), "y") && proceed != "" {
		fmt.Println("VSCODE Extensions 자동 설치는 Skip 합니다.")
		return nil
	}

	for i, vsixFile := range vsixFiles {
		fmt.Printf("[%d/%d] %s 설치 중...\n", i+1, len(vsixFiles), vsixFile)
		localFile := filepath.Join(fm.procDir, vsixFile)
		cmd := exec.Command("code", "--install-extension", localFile)
		if err := cmd.Run(); err != nil {
			fmt.Printf("%s 설치 중 오류 발생으로 Skip 합니다: %v\n", vsixFile, err)
		} else {
			fmt.Printf("✓ %s 설치 완료\n", vsixFile)
		}
	}

	return nil
}

func (fm *FileManager) registerPaths() error {
	regTargets := difference(getDirectoriesWithBin(fm.procDir), fm.existsDirs)

	if len(regTargets) == 0 {
		return nil
	}

	fmt.Println("\n레지스트리 등록을 시작합니다...")
	proceed, err := getUserInput(fmt.Sprintf("%v에 대한 레지스트리를 자동으로 등록할까요? (Y/N) ", regTargets))
	if err != nil {
		return err
	}

	if !strings.HasPrefix(strings.ToLower(proceed), "y") && proceed != "" {
		fmt.Printf("%v에 대한 자동 레지스트리 등록은 Skip 합니다.\n", regTargets)
		return nil
	}

	// 레지스트리 백업
	fmt.Println("기존 HKCU\\Environment 레지스트리에 대한 백업을 작성합니다.")
	now := time.Now()
	backupFileName := filepath.Join(fm.procDir, fmt.Sprintf("env_%d%02d%02d.reg", now.Year(), now.Month(), now.Day()))

	if _, err := os.Stat(backupFileName); err == nil {
		os.Rename(backupFileName, backupFileName+".bak")
	}

	cmd := exec.Command("reg", "export", "HKCU\\Environment", backupFileName)
	if err := cmd.Run(); err != nil {
		return err
	}
	fmt.Printf("✓ 현재 레지스트리를 %s 파일로 백업하였습니다.\n", backupFileName)

	// 현재 Path 값 가져오기
	pathValue, err := getRegistryValue("HKCU\\Environment", "Path")
	if err != nil {
		return err
	}

	pathValues := strings.Split(pathValue, ";")
	
	for _, target := range regTargets {
		if !contains(pathValues, target) {
			pathValues = append(pathValues, target)
		}
	}

	// 빈 문자열 제거 및 재구성
	var filteredPaths []string
	for _, p := range pathValues {
		if strings.TrimSpace(p) != "" {
			filteredPaths = append(filteredPaths, p)
		}
	}
	newPath := strings.Join(filteredPaths, ";") + ";"

	cmd = exec.Command("setx", "Path", newPath)
	if err := cmd.Run(); err != nil {
		return err
	}

	fmt.Println("✓ 레지스트리 등록 완료")
	return nil
}

// 유틸리티 함수들
func getUserInput(prompt string) (string, error) {
	fmt.Print(prompt)
	reader := bufio.NewReader(os.Stdin)
	input, err := reader.ReadString('\n')
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(input), nil
}

func filterFiles(files []string, predicate func(string) bool) []string {
	var result []string
	for _, file := range files {
		if predicate(file) {
			result = append(result, file)
		}
	}
	return result
}

func uniqueStrings(slice []string) []string {
	keys := make(map[string]bool)
	var result []string
	for _, entry := range slice {
		if _, exists := keys[entry]; !exists {
			keys[entry] = true
			result = append(result, entry)
		}
	}
	return result
}

func contains(slice []string, item string) bool {
	for _, s := range slice {
		if s == item {
			return true
		}
	}
	return false
}

func difference(a, b []string) []string {
	mb := make(map[string]bool, len(b))
	for _, x := range b {
		mb[x] = true
	}
	var result []string
	for _, x := range a {
		if !mb[x] {
			result = append(result, x)
		}
	}
	return result
}

func extractTarGz(src, dest string) error {
	file, err := os.Open(src)
	if err != nil {
		return err
	}
	defer file.Close()

	gzr, err := gzip.NewReader(file)
	if err != nil {
		return err
	}
	defer gzr.Close()

	tr := tar.NewReader(gzr)

	for {
		header, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}

		target := filepath.Join(dest, header.Name)

		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0755); err != nil {
				return err
			}
		case tar.TypeReg:
			if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
				return err
			}
			outFile, err := os.Create(target)
			if err != nil {
				return err
			}
			if _, err := io.Copy(outFile, tr); err != nil {
				outFile.Close()
				return err
			}
			outFile.Close()
		}
	}

	return nil
}

func extractZip(src, dest string) error {
	r, err := zip.OpenReader(src)
	if err != nil {
		return err
	}
	defer r.Close()

	for _, f := range r.File {
		fpath := filepath.Join(dest, f.Name)

		if f.FileInfo().IsDir() {
			os.MkdirAll(fpath, os.ModePerm)
			continue
		}

		if err := os.MkdirAll(filepath.Dir(fpath), os.ModePerm); err != nil {
			return err
		}

		outFile, err := os.OpenFile(fpath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, f.Mode())
		if err != nil {
			return err
		}

		rc, err := f.Open()
		if err != nil {
			outFile.Close()
			return err
		}

		_, err = io.Copy(outFile, rc)
		outFile.Close()
		rc.Close()

		if err != nil {
			return err
		}
	}

	return nil
}

func getDirectoriesWithBin(targetPath string) []string {
	var result []string
	entries, err := os.ReadDir(targetPath)
	if err != nil {
		return result
	}

	for _, entry := range entries {
		if entry.IsDir() {
			fullPath := filepath.Join(targetPath, entry.Name())
			binPath := filepath.Join(fullPath, "bin")
			if _, err := os.Stat(binPath); err == nil {
				result = append(result, binPath)
			}
		}
	}

	return result
}

func getRegistryValue(key, valueName string) (string, error) {
	cmd := exec.Command("reg", "query", key, "/v", valueName)
	output, err := cmd.Output()
	if err != nil {
		return "", err
	}

	lines := strings.Split(string(output), "\n")
	for _, line := range lines {
		if strings.Contains(line, valueName) {
			parts := strings.Fields(line)
			if len(parts) >= 3 {
				return strings.Join(parts[2:], " "), nil
			}
		}
	}

	return "", fmt.Errorf("registry value not found")
}

func dirExists(path string) bool {
	info, err := os.Stat(path)
	if os.IsNotExist(err) {
		return false
	}
	return info.IsDir()
}

func driveExists(drive string) bool {
	// Windows에서 드라이브 존재 여부 확인
	_, err := os.Stat(drive + "\\")
	return err == nil
}