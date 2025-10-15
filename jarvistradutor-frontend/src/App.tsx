// --- FILE: src/App.tsx ---
import { Navigate, Route, Routes } from "react-router-dom";
import { Toaster } from "./components/ui/toaster";
import { Layout } from "./components/Layout";
import Dashboard from "./pages/Dashboard";
import GlossaryBulk from "./pages/GlossaryBulk";
import TMImport from "./pages/TMImport";
import TMSearchLearn from "./pages/TMSearchLearn";
import EPubImport from "./pages/EPubImport";
import SeriesBooks from "./pages/SeriesBooks";
import Settings from "./pages/Settings";
import InboxBookpairs from "./pages/InboxBookpairs";
import InboxConsolidate from "./pages/InboxConsolidate";

export default function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/" element={<Dashboard />} />
        <Route path="/inbox/bookpairs" element={<InboxBookpairs />} />
        <Route path="/inbox/consolidate" element={<InboxConsolidate />} />
        <Route path="/glossary/bulk" element={<GlossaryBulk />} />
        <Route path="/tm/import" element={<TMImport />} />
        <Route path="/tm/tools" element={<TMSearchLearn />} />
        <Route path="/epub/import" element={<EPubImport />} />
        <Route path="/catalog" element={<SeriesBooks />} />
        <Route path="/settings" element={<Settings />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
      <Toaster />
    </Layout>
  );
}
