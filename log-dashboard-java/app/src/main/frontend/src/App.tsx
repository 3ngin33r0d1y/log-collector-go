import React, { useState, useEffect, useCallback } from 'react';
import './App.css';

// Define interfaces for API responses
interface LogFileEntry {
    key: string;
    fileName: string;
    lastModified: string; // Assuming ISO 8601 format from backend
    size: number;
    sequence: number;
}

function App() {
    const [buckets, setBuckets] = useState<string[]>([]);
    const [selectedBucket, setSelectedBucket] = useState<string>('');
    const [env, setEnv] = useState<string>('DEV'); // Default or load from storage
    const [appName, setAppName] = useState<string>('cash2atm'); // Default or load
    const [date, setDate] = useState<string>(new Date().toISOString().split('T')[0]); // Default to today YYYY-MM-DD
    const [logFiles, setLogFiles] = useState<LogFileEntry[]>([]);
    const [selectedLogKey, setSelectedLogKey] = useState<string | null>(null);
    const [logContent, setLogContent] = useState<string>('');
    const [loading, setLoading] = useState<boolean>(false);
    const [error, setError] = useState<string | null>(null);
    const [searchQuery, setSearchQuery] = useState<string>('');
    const [searchResults, setSearchResults] = useState<string[]>([]);
    const [searching, setSearching] = useState<boolean>(false);

    // Fetch available buckets on component mount
    useEffect(() => {
        setLoading(true);
        fetch('/api/config/buckets')
            .then(response => {
                if (!response.ok) {
                    throw new Error('Failed to fetch buckets');
                }
                return response.json();
            })
            .then((data: string[]) => {
                setBuckets(data);
                if (data.length > 0) {
                    setSelectedBucket(data[0]); // Select first bucket by default
                }
                setError(null);
            })
            .catch(err => {
                console.error("Error fetching buckets:", err);
                setError(err.message);
                setBuckets([]);
            })
            .finally(() => setLoading(false));
    }, []);

    // Function to fetch log files based on current filters
    const fetchLogFiles = useCallback(() => {
        if (!selectedBucket || !env || !appName || !date) {
            setLogFiles([]);
            return;
        }
        setLoading(true);
        setError(null);
        setLogFiles([]); // Clear previous logs
        setSelectedLogKey(null);
        setLogContent('');
        setSearchResults([]); // Clear search results

        const params = new URLSearchParams({
            bucket: selectedBucket,
            env: env,
            appName: appName,
            date: date
        });

        fetch(`/api/logs?${params.toString()}`)
            .then(response => {
                if (!response.ok) {
                    return response.text().then(text => { throw new Error(`Failed to fetch logs: ${response.status} ${text}`); });
                }
                return response.json();
            })
            .then((data: LogFileEntry[]) => {
                setLogFiles(data);
            })
            .catch(err => {
                console.error("Error fetching log files:", err);
                setError(err.message);
                setLogFiles([]);
            })
            .finally(() => setLoading(false));
    }, [selectedBucket, env, appName, date]);

    // Fetch log files when filters change
    useEffect(() => {
        fetchLogFiles();
    }, [fetchLogFiles]); // Dependency array includes the memoized function

    // Function to fetch content of a selected log file
    const fetchLogContent = useCallback((key: string) => {
        if (!selectedBucket) return;
        setLoading(true);
        setError(null);
        setLogContent('Loading content...');
        setSelectedLogKey(key);

        const params = new URLSearchParams({ bucket: selectedBucket, key: key });
        fetch(`/api/log-content?${params.toString()}`)
            .then(async response => {
                const text = await response.text();
                if (!response.ok) {
                    throw new Error(`Failed to fetch log content: ${response.status} ${text}`);
                }
                return text;
            })
            .then(data => {
                setLogContent(data);
            })
            .catch(err => {
                console.error("Error fetching log content:", err);
                setError(err.message);
                setLogContent(`Error loading content: ${err.message}`);
            })
            .finally(() => setLoading(false));
    }, [selectedBucket]);

    // Function to handle search
    const handleSearch = useCallback(() => {
        if (!selectedBucket || !env || !appName || !date || !searchQuery) {
            setSearchResults([]);
            return;
        }
        setSearching(true);
        setError(null);
        setSearchResults([]);

        const params = new URLSearchParams({
            bucket: selectedBucket,
            env: env,
            appName: appName,
            date: date,
            query: searchQuery
        });

        fetch(`/api/search?${params.toString()}`)
            .then(response => {
                if (!response.ok) {
                    return response.text().then(text => { throw new Error(`Search failed: ${response.status} ${text}`); });
                }
                return response.json();
            })
            .then((data: string[]) => {
                setSearchResults(data);
                if (data.length === 0) {
                    setError("No results found for your search query.");
                }
            })
            .catch(err => {
                console.error("Error during search:", err);
                setError(err.message);
                setSearchResults([]);
            })
            .finally(() => setSearching(false));
    }, [selectedBucket, env, appName, date, searchQuery]);

    // Navigation functions
    const navigateLog = (direction: 'next' | 'prev') => {
        if (!selectedLogKey || logFiles.length === 0) return;

        const currentIndex = logFiles.findIndex(log => log.key === selectedLogKey);
        if (currentIndex === -1) return;

        let nextIndex = -1;
        if (direction === 'next') {
            nextIndex = currentIndex + 1;
        } else {
            nextIndex = currentIndex - 1;
        }

        if (nextIndex >= 0 && nextIndex < logFiles.length) {
            fetchLogContent(logFiles[nextIndex].key);
        }
    };

    const currentLogIndex = selectedLogKey ? logFiles.findIndex(log => log.key === selectedLogKey) : -1;

    return (
        <div className="App">
            <h1>Log Dashboard</h1>

            {error && <div className="error-message">Error: {error}</div>}

            <div className="filters">
                <label>
                    Bucket:
                    <select value={selectedBucket} onChange={e => setSelectedBucket(e.target.value)} disabled={loading}>
                        {buckets.map(bucket => (
                            <option key={bucket} value={bucket}>{bucket}</option>
                        ))}
                    </select>
                </label>
                <label>
                    Environment:
                    <select value={env} onChange={e => setEnv(e.target.value)} disabled={loading}>
                        <option value="DEV">DEV</option>
                        <option value="HF">HF</option>
                        <option value="HT">HT</option>
                        <option value="PROD">PROD</option>
                    </select>
                </label>
                <label>
                    App Name:
                    <input type="text" value={appName} onChange={e => setAppName(e.target.value)} disabled={loading} />
                </label>
                <label>
                    Date:
                    <input type="date" value={date} onChange={e => setDate(e.target.value)} disabled={loading} />
                </label>
                <button onClick={fetchLogFiles} disabled={loading}>
                    {loading ? 'Loading...' : 'Refresh Logs'}
                </button>
            </div>

            <div className="search-section">
                 <label>
                    Search Query:
                    <input
                        type="text"
                        value={searchQuery}
                        onChange={e => setSearchQuery(e.target.value)}
                        disabled={searching || loading}
                        placeholder="Enter search term..."
                    />
                </label>
                <button onClick={handleSearch} disabled={searching || loading || !searchQuery}>
                    {searching ? 'Searching...' : 'Search Logs'}
                </button>
            </div>

            {searchResults.length > 0 && (
                <div className="search-results">
                    <h3>Search Results (Files containing query):</h3>
                    <ul>
                        {searchResults.map((fileName, index) => (
                            <li key={index}>{fileName}</li>
                        ))}
                    </ul>
                </div>
            )}

            <div className="main-content">
                <div className="log-list">
                    <h2>Log Files ({logFiles.length})</h2>
                    {loading && logFiles.length === 0 && <p>Loading log list...</p>}
                    <ul>
                        {logFiles.map(log => (
                            <li
                                key={log.key}
                                onClick={() => fetchLogContent(log.key)}
                                className={log.key === selectedLogKey ? 'selected' : ''}
                            >
                                {log.fileName} ({log.size} bytes)
                            </li>
                        ))}
                    </ul>
                </div>

                <div className="log-content-view">
                    <h2>Log Content</h2>
                    {selectedLogKey && (
                         <div className="navigation-buttons">
                            <button onClick={() => navigateLog('prev')} disabled={loading || currentLogIndex <= 0}>
                                Previous
                            </button>
                            <span>{logFiles[currentLogIndex]?.fileName} ({currentLogIndex + 1} / {logFiles.length})</span>
                            <button onClick={() => navigateLog('next')} disabled={loading || currentLogIndex < 0 || currentLogIndex >= logFiles.length - 1}>
                                Next
                            </button>
                        </div>
                    )}
                    {loading && selectedLogKey && <p>Loading content...</p>}
                    <pre>{logContent}</pre>
                </div>
            </div>
        </div>
    );
}

export default App;

