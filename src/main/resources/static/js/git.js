document.addEventListener('DOMContentLoaded', () => {
    const repoList = document.getElementById('repo-list-container');
    const modal = document.getElementById('repo-modal');
    const modalClose = document.getElementById('repo-modal-close');
    const modalTitle = document.getElementById('repo-modal-title');
    const backButton = document.getElementById('repo-back-button');
    const breadcrumb = document.getElementById('repo-breadcrumb');
    const fileTreeContainer = document.getElementById('repo-file-tree');
    const fileContentWrapper = document.getElementById('repo-file-content-wrapper');
    const fileContent = document.getElementById('repo-file-content');

    let currentProjectId = null;
    let pathStack = [];

    // --- Main Event Listener ---
    repoList.addEventListener('click', (event) => {
        const repoItem = event.target.closest('.repo-item');
        if (repoItem) {
            const projectId = repoItem.dataset.projectId;
            const projectName = repoItem.dataset.projectName;
            openRepoModal(projectId, projectName);
        }
    });

    // --- Modal Handling ---
    function openRepoModal(projectId, projectName) {
        currentProjectId = projectId;
        modalTitle.textContent = projectName;
        modal.style.display = 'flex';
        resetModalViews();
        fetchTree(projectId, '');
    }

    modalClose.addEventListener('click', () => {
        modal.style.display = 'none';
    });

    window.addEventListener('click', (event) => {
        if (event.target === modal) {
            modal.style.display = 'none';
        }
    });
    
    backButton.addEventListener('click', () => {
        // If viewing file, go back to tree view
        if (fileContentWrapper.style.display !== 'none') {
            fileContentWrapper.style.display = 'none';
            fileTreeContainer.style.display = 'block';
            backButton.style.display = pathStack.length > 0 ? 'block' : 'none'; // Show back button if not in root
        } 
        // If in a sub-directory, go up one level
        else if (pathStack.length > 0) {
            pathStack.pop();
            const parentPath = pathStack.length > 0 ? pathStack[pathStack.length - 1] : '';
            fetchTree(currentProjectId, parentPath);
        }
    });

    // --- API Calls and Rendering ---

    async function fetchTree(projectId, path) {
        // Only reset the file content view, not the whole modal
        fileContentWrapper.style.display = 'none';
        fileTreeContainer.style.display = 'block';
        fileTreeContainer.innerHTML = '<p>Loading tree...</p>';

        try {
            const response = await fetch(`/api/gitlab/projects/${projectId}/repository/tree?path=${encodeURIComponent(path)}`);
            if (!response.ok) throw new Error('Failed to fetch repository tree');
            const data = await response.json();
            
            if (!Array.isArray(data)) throw new Error('Invalid response from GitLab API');
            
            await renderTree(data, projectId); // Now async
            updateBreadcrumb();

        } catch (error) {
            console.error('Error fetching tree:', error);
            fileTreeContainer.innerHTML = `<p class="error">Error loading repository: ${error.message}</p>`;
        }
    }

    // Fetches content but does not render
    async function getFileContent(projectId, filePath) {
        const response = await fetch(`/api/gitlab/projects/${projectId}/repository/files/raw?path=${encodeURIComponent(filePath)}`);
        if (!response.ok) {
            throw new Error(`Failed to fetch file content. Status: ${response.status}`);
        }
        return await response.text();
    }
    
    // Renders content
    function showFileContent(content) {
        fileTreeContainer.style.display = 'none';
        fileContentWrapper.style.display = 'block';
        fileContent.textContent = content;
        backButton.style.display = 'block';
    }

    async function renderTree(items, projectId) {
        // First, check for README and try to display it
        const isRoot = pathStack.length === 0;
        if (isRoot) {
            const readme = items.find(item => item.name.toLowerCase() === 'readme.md' && item.type === 'blob');
            if (readme) {
                try {
                    const content = await getFileContent(projectId, readme.path);
                    showFileContent(content);
                    // Since README is shown, we can optionally render the tree in the background for when the user clicks "back"
                    // For now, we just return to avoid rendering the tree initially.
                    // To do that, we would call renderFileTreeUI(items, projectId) here and not return.
                    return; 
                } catch (error) {
                    console.error("Could not auto-load README.md, showing file tree instead.", error);
                    // Fall through to render the tree if README fetch fails
                }
            }
        }
        
        // If no README or if it failed, render the file tree
        renderFileTreeUI(items, projectId);
    }

    function renderFileTreeUI(items, projectId) {
        fileTreeContainer.innerHTML = '';
        
        items.sort((a, b) => {
            if (a.type === 'tree' && b.type !== 'tree') return -1;
            if (a.type !== 'tree' && b.type === 'tree') return 1;
            return a.name.localeCompare(b.name);
        });

        if (items.length === 0) {
            fileTreeContainer.innerHTML = '<p>This directory is empty.</p>';
            return;
        }

        items.forEach(item => {
            const itemEl = document.createElement('div');
            itemEl.className = 'file-tree-item';
            
            const iconClass = item.type === 'tree' ? 'fa-folder' : 'fa-file-alt';
            itemEl.innerHTML = `<i class="fas ${iconClass}"></i><span>${item.name}</span>`;

            itemEl.addEventListener('click', async () => {
                if (item.type === 'tree') {
                    pathStack.push(item.path);
                    fetchTree(projectId, item.path);
                } else {
                    try {
                        const content = await getFileContent(projectId, item.path);
                        showFileContent(content);
                    } catch (error) {
                        console.error("Error showing file content:", error);
                        alert("Could not load file content."); // Inform user of the error
                    }
                }
            });
            fileTreeContainer.appendChild(itemEl);
        });
    }

    // --- UI Helpers ---

    function resetModalViews() {
        fileTreeContainer.style.display = 'block';
        fileContentWrapper.style.display = 'none';
        fileContent.textContent = '';
        pathStack = [];
        updateBreadcrumb();
        backButton.style.display = 'none';
    }

    function updateBreadcrumb() {
        breadcrumb.innerHTML = '<span class="breadcrumb-item active">/</span>';
        if (pathStack.length === 0) return;

        let currentPath = '';
        const pathParts = pathStack[pathStack.length - 1].split('/');
        
        breadcrumb.innerHTML = ''; // Clear
        const rootEl = document.createElement('span');
        rootEl.className = 'breadcrumb-item';
        rootEl.textContent = '/';
        rootEl.addEventListener('click', () => {
            pathStack = [];
            fetchTree(currentProjectId, '');
        });
        breadcrumb.appendChild(rootEl);


        pathParts.forEach((part, index) => {
            currentPath += (index > 0 ? '/' : '') + part;
            const separator = document.createElement('span');
            separator.className = 'breadcrumb-separator';
            separator.textContent = '›';
            breadcrumb.appendChild(separator);

            const partEl = document.createElement('span');
            partEl.className = 'breadcrumb-item';
            partEl.textContent = part;
            
            if (index < pathParts.length -1) {
                const capturedPath = currentPath;
                partEl.addEventListener('click', () => {
                    // Truncate pathStack to the clicked level
                    while(pathStack.length > 0 && pathStack[pathStack.length - 1] !== capturedPath) {
                        pathStack.pop();
                    }
                    fetchTree(currentProjectId, capturedPath);
                });
            } else {
                partEl.classList.add('active');
            }
            breadcrumb.appendChild(partEl);
        });

        backButton.style.display = pathStack.length > 0 ? 'block' : 'none';
    }
});
