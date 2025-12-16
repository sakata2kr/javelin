document.addEventListener('DOMContentLoaded', () => {
    const searchInput = document.getElementById('search-input');
    const libraryList = document.getElementById('library-list');
    const modal = document.getElementById('dependency-modal');
    const closeButton = modal.querySelector('.close-button');
    const modalTitle = document.getElementById('modal-title');
    const pomCode = document.getElementById('pom-code');
    const gradleCode = document.getElementById('gradle-code');
    const modalTabs = modal.querySelector('.modal-tabs');

    let debounceTimer;

    // 1. Debounce search input
    searchInput.addEventListener('input', () => {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => {
            const query = searchInput.value.trim();
            // Allow empty query to search for all, or query with 2+ chars
            if (query === '' || query.length > 2) {
                searchLibraries(query);
            } else {
                libraryList.innerHTML = '<p class="text-center">검색어는 3글자 이상 입력해주세요.</p>';
            }
        }, 300); // 300ms delay
    });

    // 2. Search libraries from backend
    async function searchLibraries(query) {
        libraryList.innerHTML = '<p class="text-center">검색 중...</p>';
        try {
            const response = await fetch(`/api/nexus/search?q=${encodeURIComponent(query)}`);
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            const data = await response.json();
            renderResults(data.items);
        } catch (error) {
            console.error('Error fetching search results:', error);
            libraryList.innerHTML = '<p class="text-center error">라이브러리 검색 중 오류가 발생했습니다.</p>';
        }
    }

    // 3. Render search results
    function renderResults(items) {
        if (!items || items.length === 0) {
            libraryList.innerHTML = '<p class="text-center">검색 결과가 없습니다.</p>';
            return;
        }

        const validItems = items.filter(item => item.group && item.name && item.version);

        if (validItems.length === 0) {
            libraryList.innerHTML = '<p class="text-center">표시할 유효한 라이브러리가 없습니다.</p>';
            return;
        }

        // Group by artifact and find the latest version
        const artifacts = new Map();
        validItems.forEach(item => {
            const key = `${item.group}:${item.name}`;
            const existing = artifacts.get(key);
            if (!existing || item.version.localeCompare(existing.version, undefined, { numeric: true }) > 0) {
                artifacts.set(key, item);
            }
        });

        const latestArtifacts = Array.from(artifacts.values());

        libraryList.innerHTML = ''; // Clear previous results
        latestArtifacts.forEach(item => {
            const libraryItem = document.createElement('div');
            libraryItem.className = 'library-item';
            
            libraryItem.dataset.group = item.group;
            libraryItem.dataset.name = item.name;
            libraryItem.dataset.version = item.version;
            libraryItem.dataset.repository = item.repository;

            const isSnapshot = item.repository.toLowerCase().includes('snapshot');
            const repoButton = isSnapshot
                ? '<button class="repo-button repo-snapshot">SNAPSHOT</button>'
                : '<button class="repo-button repo-release">RELEASE</button>';

            libraryItem.innerHTML = `
                <div class="library-icon-wrapper purple">
                    <i class="fas fa-box-open"></i>
                </div>
                <div class="library-info">
                    <div class="library-name-tag">
                        <h3>${item.name}</h3>
                    </div>
                    <p class="library-description">${item.group}</p>
                    <div class="library-meta">
                        <span><i class="fas fa-code-branch"></i> Version: ${item.version}</span>
                        ${repoButton}
                    </div>
                </div>
                <i class="fas fa-chevron-right library-arrow"></i>
            `;
            libraryList.appendChild(libraryItem);
        });
    }
    
    // 4. Handle clicks within the library list (Event Delegation)
    libraryList.addEventListener('click', (event) => {
        const versionItem = event.target.closest('.version-history-item');
        const libraryItem = event.target.closest('.library-item');

        if (versionItem) {
            // A click on a sub-item (a specific version)
            const { group, name, version } = versionItem.dataset;
            openModal(group, name, version);
        } else if (libraryItem) {
            // A click on a main library item
            toggleVersionHistory(libraryItem);
        }
    });

    // 5. Toggle, Fetch, and Render Version History
    async function toggleVersionHistory(itemElement) {
        const existingHistory = itemElement.nextElementSibling;
        if (existingHistory && existingHistory.classList.contains('version-history-container')) {
            existingHistory.remove();
            itemElement.querySelector('.library-arrow').classList.remove('fa-chevron-down');
            itemElement.querySelector('.library-arrow').classList.add('fa-chevron-right');
            return;
        }

        const { group, name } = itemElement.dataset;
        if (!group || !name) {
            console.error('Cannot fetch version history: group or name is missing.', itemElement.dataset);
            return;
        }

        try {
            // Show loading state
            itemElement.querySelector('.library-arrow').classList.remove('fa-chevron-right');
            itemElement.querySelector('.library-arrow').classList.add('fa-spinner', 'fa-spin');

            // Backend will now search both release and snapshot repos
            const response = await fetch(`/api/nexus/search?group=${encodeURIComponent(group)}&name=${encodeURIComponent(name)}`);
            if (!response.ok) {
                throw new Error('Failed to fetch versions');
            }
            const data = await response.json();
            
            // Sort versions
            const sortedItems = data.items.sort((a, b) => b.version.localeCompare(a.version, undefined, { numeric: true }));

            renderVersionHistory(sortedItems, itemElement);

        } catch (error) {
            console.error('Error fetching version history:', error);
            // Optionally, render an error message in the history container
        } finally {
            // Remove loading state and set chevron
            itemElement.querySelector('.library-arrow').classList.remove('fa-spinner', 'fa-spin');
            itemElement.querySelector('.library-arrow').classList.add('fa-chevron-down');
        }
    }

    function renderVersionHistory(items, parentElement) {
        const historyContainer = document.createElement('div');
        historyContainer.className = 'version-history-container';

        if (!items || items.length === 0) {
            historyContainer.innerHTML = '<div class="version-history-item">No other versions found.</div>';
        } else {
            items.forEach(item => {
                const versionItem = document.createElement('div');
                versionItem.className = 'version-history-item';
                versionItem.dataset.group = item.group;
                versionItem.dataset.name = item.name;
                versionItem.dataset.version = item.version;
                versionItem.dataset.repository = item.repository;

                const isSnapshot = item.repository.toLowerCase().includes('snapshot');
                const repoButton = isSnapshot
                    ? '<button class="repo-button-small repo-snapshot">SNAPSHOT</button>'
                    : '<button class="repo-button-small repo-release">RELEASE</button>';

                versionItem.innerHTML = `
                    ${repoButton}
                    <span class="version-text">${item.version}</span>
                `;
                historyContainer.appendChild(versionItem);
            });
        }
        // Insert after the parent library item
        parentElement.after(historyContainer);
    }


    // 6. Open and populate the modal
    async function openModal(group, name, version) {
        modalTitle.textContent = `${name}:${version}`;
        pomCode.textContent = 'Loading...';
        gradleCode.textContent = 'Loading...';
        modal.style.display = 'flex';

        try {
            const response = await fetch(`/api/nexus/dependency?g=${group}&a=${name}&v=${version}`);
            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }
            const deps = await response.json();
            pomCode.textContent = deps.pom;
            gradleCode.textContent = deps.gradle;
        } catch (error) {
            console.error('Error fetching dependency info:', error);
            pomCode.textContent = 'Failed to load dependency info.';
            gradleCode.textContent = 'Failed to load dependency info.';
        }
    }

    // 6. Close modal
    closeButton.addEventListener('click', () => {
        modal.style.display = 'none';
    });

    window.addEventListener('click', (event) => {
        if (event.target === modal) {
            modal.style.display = 'none';
        }
    });
    
    // 7. Handle modal tabs
    modalTabs.addEventListener('click', (event) => {
        if (event.target.classList.contains('tab-link')) {
            const tabName = event.target.dataset.tab;

            // Deactivate all tabs and content
            modal.querySelectorAll('.tab-link').forEach(tab => tab.classList.remove('active'));
            modal.querySelectorAll('.tab-content').forEach(content => content.classList.remove('active'));

            // Activate clicked tab and content
            event.target.classList.add('active');
            document.getElementById(tabName).classList.add('active');
        }
    });

    // Initial search to populate the list
    searchLibraries('');
});
