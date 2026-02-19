import './style.css';
import { WebviewVersionChecker } from '@capgo/capacitor-webview-version-checker';

const output = document.getElementById('plugin-output');
const checkButton = document.getElementById('check-status');
const monitorButton = document.getElementById('start-monitoring');
const promptButton = document.getElementById('show-prompt');

const setOutput = (value) => {
  output.textContent = typeof value === 'string' ? value : JSON.stringify(value, null, 2);
};

let listenerHandle;

const ensureListener = async () => {
  if (listenerHandle) {
    return;
  }

  listenerHandle = await WebviewVersionChecker.addListener('statusChanged', (status) => {
    setOutput({ event: 'statusChanged', status });
  });
};

checkButton.addEventListener('click', async () => {
  try {
    await ensureListener();
    const status = await WebviewVersionChecker.check();
    setOutput(status);
  } catch (error) {
    setOutput(`Error: ${error?.message ?? error}`);
  }
});

monitorButton.addEventListener('click', async () => {
  try {
    await ensureListener();
    const state = await WebviewVersionChecker.startMonitoring({
      checkOnStart: true,
      autoPromptOnOutdated: true,
    });
    setOutput(state);
  } catch (error) {
    setOutput(`Error: ${error?.message ?? error}`);
  }
});

promptButton.addEventListener('click', async () => {
  try {
    const result = await WebviewVersionChecker.showUpdatePrompt();
    setOutput(result);
  } catch (error) {
    setOutput(`Error: ${error?.message ?? error}`);
  }
});
