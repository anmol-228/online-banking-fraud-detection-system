import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, HashRouter } from 'react-router-dom';
import App from './App.jsx';
import { AuthProvider } from './auth/AuthContext.jsx';
import { IS_SHOWCASE } from './config/appMode.js';
import './index.css';

/**
 * Router choice by deployment target.
 *
 * GitHub Pages serves static files only, so a deep link such as `/transactions/TXN-123` would be
 * requested from the server and return 404. A hash router keeps the whole route on the client,
 * which means every page works on a direct visit, on refresh and through browser history without
 * any server rewrite rules.
 *
 * Local and full-stack builds keep clean paths with the standard browser router.
 */
const Router = IS_SHOWCASE ? HashRouter : BrowserRouter;

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <Router>
      <AuthProvider>
        <App />
      </AuthProvider>
    </Router>
  </React.StrictMode>
);
