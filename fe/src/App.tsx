import React, { useState, useEffect } from 'react';
import Header from './components/Header';
import Footer from './components/Footer';
import HomeView from './views/HomeView';
import MangaDetailView from './views/MangaDetailView';
import ReaderView from './views/ReaderView';
import FollowingView from './views/FollowingView';
import HistoryView from './views/HistoryView';
import ManagementView from './views/ManagementView';
import { ReadingHistoryItem, Manga, Genre } from './types';
import api from './utils/api';

export default function App() {
  const [currentView, setCurrentView] = useState<'home' | 'detail' | 'reader' | 'following' | 'history' | 'management'>(() => {
    const saved = localStorage.getItem('lastView');
    const validViews = ['home', 'detail', 'reader', 'following', 'history', 'management'];
    const accountOnlyViews = ['following', 'history', 'management'];
    if (saved && accountOnlyViews.includes(saved) && !localStorage.getItem('token')) {
      return 'home';
    }
    return (saved && validViews.includes(saved)) ? (saved as any) : 'home';
  });
  const [selectedMangaId, setSelectedMangaId] = useState<string | null>(() => localStorage.getItem('lastMangaId'));
  const [selectedChapterId, setSelectedChapterId] = useState<string | null>(() => localStorage.getItem('lastChapterId'));

  // Persist current navigation state so a page reload stays on the same screen
  useEffect(() => {
    localStorage.setItem('lastView', currentView);
    if (selectedMangaId) {
      localStorage.setItem('lastMangaId', selectedMangaId);
    } else {
      localStorage.removeItem('lastMangaId');
    }
    if (selectedChapterId) {
      localStorage.setItem('lastChapterId', selectedChapterId);
    } else {
      localStorage.removeItem('lastChapterId');
    }
  }, [currentView, selectedMangaId, selectedChapterId]);
  
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedGenre, setSelectedGenre] = useState<string | null>(null);
  
  const [followedIds, setFollowedIds] = useState<string[]>([]);
  const [historyItems, setHistoryItems] = useState<ReadingHistoryItem[]>([]);
  
  // Real or fallback logged-in user credentials
  const [username, setUsername] = useState<string | null>(localStorage.getItem('username'));
  const [role, setRole] = useState<string | null>(localStorage.getItem('role'));
  const userEmail = username ? `${username}@gmail.com` : "35.10a12.thanhnguyenthanh@gmail.com";

  // Dynamic mangas and categories loaded from backend
  const [mangas, setMangas] = useState<Manga[]>([]);
  const [categories, setCategories] = useState<Genre[]>([]);
  const [loading, setLoading] = useState<boolean>(true);

  // Load real backend data
  const fetchBackendData = async () => {
    setLoading(true);
    try {
      // 1. Fetch categories
      const catRes = await api.get('/categories');
      const catList: Genre[] = catRes.data.map((c: any) => ({
        id: c.name,
        name: c.name,
        description: `Thể loại ${c.name}`
      }));
      setCategories(catList);

      // 2. Fetch stories (up to 100 for proper catalog indexing)
      const storiesRes = await api.get('/stories?size=100');
      const storyCards = storiesRes.data.content || [];

      // 2b. Fetch real trending story IDs (based on actual view counts over the
      // past week, from the backend's view_log-backed /stories/trending endpoint)
      // to drive the "Truyện đề cử hot nhất" carousel + HOT badge — replacing the
      // old static "averageRating >= 4.7" placeholder.
      let trendingIds = new Set<number>();
      let trendingRankMapWeek = new Map<number, number>();
      let trendingRankMapMonth = new Map<number, number>();
      try {
        const [trendingWeekRes, trendingMonthRes] = await Promise.all([
          api.get('/stories/trending?period=week&size=20'),
          api.get('/stories/trending?period=month&size=20')
        ]);
        (trendingWeekRes.data.content || []).forEach((s: any, idx: number) => {
          trendingIds.add(s.storyId);
          trendingRankMapWeek.set(s.storyId, idx);
        });
        (trendingMonthRes.data.content || []).forEach((s: any, idx: number) => {
          trendingRankMapMonth.set(s.storyId, idx);
        });
      } catch (e) {
        console.error('Failed to fetch trending stories, HOT badge/ranking will be empty this load', e);
      }

      // 3. Concurrently fetch details and chapters for each story
      const fullMangas = await Promise.all(
        storyCards.map(async (card: any) => {
          try {
            const detailRes = await api.get(`/stories/${card.storyId}`);
            const story = detailRes.data;
            const chapRes = await api.get(`/stories/${card.storyId}/chapters`);

            const chapters = chapRes.data.map((ch: any) => ({
              id: ch.chapterId.toString(),
              mangaId: story.slug || story.storyId.toString(),
              title: ch.title || `Chapter ${ch.chapterNumber}`,
              updatedAt: ch.createdAt || new Date().toISOString(),
              pages: ch.imageUrls || [],
              viewCount: 0
            }));

            return {
              id: story.slug || story.storyId.toString(),
              storyId: story.storyId,
              title: story.title,
              otherTitle: story.title,
              author: story.author || 'Khuyết Danh',
              status: story.status === 'COMPLETED' || story.status === 'Hoàn thành' ? 'Hoàn thành' : 'Đang tiến hành',
              description: story.description || '',
              coverUrl: story.coverImage || 'https://images.unsplash.com/photo-1509198397868-475647b2a1e5?w=600&auto=format&fit=crop&q=80',
              bannerUrl: story.coverImage || '',
              genres: story.categories ? story.categories.map((c: any) => c.name) : [],
              viewCount: story.viewCount || 0,
              commentCount: 0,
              followerCount: story.followCount || 0,
              rating: story.averageRating || 4.5,
              chapters: chapters,
              isHot: trendingIds.has(story.storyId),
              trendingRankWeek: trendingRankMapWeek.get(story.storyId),
              trendingRankMonth: trendingRankMapMonth.get(story.storyId),
              ageRating: story.ageRating || 0,
              ageRestricted: !!story.ageRestricted
            };
          } catch (e) {
            console.error('Error fetching details for storyId', card.storyId, e);
            return null;
          }
        })
      );

      const validMangas = fullMangas.filter(Boolean) as Manga[];
      setMangas(validMangas);
      await loadFollowsAndHistory(validMangas);
    } catch (err) {
      console.error('Failed to fetch initial stories/categories', err);
    } finally {
      setLoading(false);
    }
  };

  // Load followed stories & reading history from backend (if logged in) and map them
  // onto the local Manga.id (slug) used throughout the UI.
  const loadFollowsAndHistory = async (mangaList: Manga[]) => {
    const token = localStorage.getItem('token');
    if (!token) return;

    try {
      const followsRes = await api.get('/follows?size=100');
      const followedStoryIds: number[] = (followsRes.data.content || []).map((f: any) => f.storyId);
      const mappedFollowIds = mangaList
        .filter(m => followedStoryIds.includes(m.storyId))
        .map(m => m.id);
      setFollowedIds(mappedFollowIds);
      localStorage.setItem('followed_mangas', JSON.stringify(mappedFollowIds));
    } catch (e) {
      console.error('Failed to load follows from backend', e);
    }

    try {
      const historyRes = await api.get('/history?size=100');
      const mappedHistory: ReadingHistoryItem[] = (historyRes.data.content || []).map((h: any) => {
        const manga = mangaList.find(m => m.storyId === h.storyId);
        return {
          mangaId: manga ? manga.id : h.storyId.toString(),
          chapterId: h.chapterId ? h.chapterId.toString() : '',
          chapterTitle: h.chapterTitle || `Chapter ${h.chapterNumber ?? ''}`,
          mangaTitle: h.storyTitle,
          coverUrl: h.coverImage || (manga ? manga.coverUrl : ''),
          readAt: h.updatedAt
        };
      });
      setHistoryItems(mappedHistory);
      localStorage.setItem('reading_history', JSON.stringify(mappedHistory));
    } catch (e) {
      console.error('Failed to load history from backend', e);
    }
  };

  // Validate token with /auth/me on app mount and then load initial stories/categories
  useEffect(() => {
    const validateTokenAndLoad = async () => {
      const token = localStorage.getItem('token');
      if (token) {
        try {
          const profileRes = await api.get('/auth/me');
          setUsername(profileRes.data.username);
          setRole(profileRes.data.role);
          localStorage.setItem('username', profileRes.data.username);
          localStorage.setItem('role', profileRes.data.role);
        } catch (e) {
          console.error('Session expired or invalid token. Logging out...', e);
          localStorage.removeItem('token');
          localStorage.removeItem('username');
          localStorage.removeItem('role');
          setUsername(null);
          setRole(null);
        }
      }
      fetchBackendData();
    };

    validateTokenAndLoad();
  }, []);

  const handleAuthChange = () => {
    setUsername(localStorage.getItem('username'));
    setRole(localStorage.getItem('role'));
    fetchBackendData();
  };

  // Load state from localStorage on init
  useEffect(() => {
    const savedFollows = localStorage.getItem('followed_mangas');
    if (savedFollows) {
      try {
        setFollowedIds(JSON.parse(savedFollows));
      } catch (e) {
        console.error('Failed to parse followed_mangas', e);
      }
    }

    const savedHistory = localStorage.getItem('reading_history');
    if (savedHistory) {
      try {
        setHistoryItems(JSON.parse(savedHistory));
      } catch (e) {
        console.error('Failed to parse reading_history', e);
      }
    }
  }, []);

  // Set the fixed dark theme once on mount (single-theme app; no toggle)
  useEffect(() => {
    document.documentElement.classList.add('dark');
    document.body.style.backgroundColor = '#0a0a0a';
    document.body.style.color = '#e5e5e5';
  }, []);

  // Ask Header to pop open its login modal with a specific message. Used whenever an
  // unauthenticated visitor tries to reach a feature that requires an account
  // (theo dõi, lịch sử, xem truyện giới hạn độ tuổi...).
  const requireLogin = (message: string) => {
    window.dispatchEvent(new CustomEvent('require-login', { detail: { message } }));
  };

  // Navigate utility
  const handleNavigate = (
    view: 'home' | 'detail' | 'reader' | 'following' | 'history' | 'management',
    mangaId?: string,
    chapterId?: string
  ) => {
    if ((view === 'following' || view === 'history') && !localStorage.getItem('token')) {
      requireLogin('Vui lòng đăng nhập để sử dụng tính năng này.');
      setCurrentView('home');
      window.scrollTo({ top: 0, behavior: 'smooth' });
      return;
    }

    setCurrentView(view);
    if (mangaId) setSelectedMangaId(mangaId);
    if (chapterId) setSelectedChapterId(chapterId);
    
    // Auto-scroll to top when moving between screens
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  // Toggle Followed list
  const handleToggleFollow = async (mangaId: string) => {
    if (!localStorage.getItem('token')) {
      requireLogin('Vui lòng đăng nhập để sử dụng tính năng này.');
      return;
    }

    const isNowFollowing = !followedIds.includes(mangaId);
    let updated: string[];
    if (!isNowFollowing) {
      updated = followedIds.filter(id => id !== mangaId);
    } else {
      updated = [...followedIds, mangaId];
    }
    setFollowedIds(updated);
    localStorage.setItem('followed_mangas', JSON.stringify(updated));

    const token = localStorage.getItem('token');
    const manga = mangas.find(m => m.id === mangaId);
    if (token && manga) {
      try {
        if (isNowFollowing) {
          await api.post(`/follows/${manga.storyId}`);
        } else {
          await api.delete(`/follows/${manga.storyId}`);
        }
      } catch (e) {
        console.error('Failed to sync follow status with backend', e);
      }
    }
  };

  // Add Item to Reading History list
  const handleAddToHistory = (
    mangaId: string,
    chapterId: string,
    chapterTitle: string,
    mangaTitle: string,
    coverUrl: string
  ) => {
    // Reading history is an account feature — don't build up local history for
    // anonymous visitors (they'd never be able to open "Lịch Sử" to see it anyway).
    if (!localStorage.getItem('token')) return;

    // Filter out existing history entry for the SAME manga to avoid duplicate listings
    const cleanHistory = historyItems.filter(item => item.mangaId !== mangaId);
    
    const newItem: ReadingHistoryItem = {
      mangaId,
      chapterId,
      chapterTitle,
      mangaTitle,
      coverUrl,
      readAt: new Date().toISOString()
    };

    const updated = [newItem, ...cleanHistory];
    setHistoryItems(updated);
    localStorage.setItem('reading_history', JSON.stringify(updated));
  };

  // Clear specific or total reading histories
  const handleClearHistory = async (mangaId?: string) => {
    let updated: ReadingHistoryItem[];
    if (mangaId) {
      updated = historyItems.filter(item => item.mangaId !== mangaId);
    } else {
      updated = [];
    }
    setHistoryItems(updated);
    localStorage.setItem('reading_history', JSON.stringify(updated));

    const token = localStorage.getItem('token');
    if (token) {
      try {
        if (mangaId) {
          const manga = mangas.find(m => m.id === mangaId);
          if (manga) {
            await api.delete(`/history/${manga.storyId}`);
          }
        } else {
          await api.delete('/history');
        }
      } catch (e) {
        console.error('Failed to sync history clear with backend', e);
      }
    }
  };

  return (
    <div className="min-h-screen flex flex-col font-sans bg-neutral-950 text-neutral-200" id="nettruyen-app">
      
      {/* Shared Header component */}
      <Header
        currentView={currentView}
        onNavigate={handleNavigate}
        onSearch={setSearchQuery}
        onGenreSelect={setSelectedGenre}
        selectedGenre={selectedGenre}
        followedCount={followedIds.length}
        genres={categories}
        onAuthChange={handleAuthChange}
      />

      {/* Main active layout routing viewport */}
      <main className="flex-1 w-full">
        {loading ? (
          <div className="flex flex-col items-center justify-center py-24 text-neutral-400">
            <div className="animate-spin rounded-full h-10 w-10 border-b-2 border-orange-500 mb-4"></div>
            <p className="text-xs font-bold uppercase tracking-wider">Đang tải dữ liệu thực tế từ máy chủ...</p>
          </div>
        ) : (
          <>
            {currentView === 'home' && (
              <HomeView
                mangas={mangas}
                onNavigate={handleNavigate}
                searchQuery={searchQuery}
                selectedGenre={selectedGenre}
                onGenreSelect={setSelectedGenre}
                genresList={categories}
              />
            )}

            {currentView === 'detail' && selectedMangaId && (
              <MangaDetailView
                mangaId={selectedMangaId}
                mangas={mangas}
                onNavigate={handleNavigate}
                onGenreSelect={setSelectedGenre}
                userEmail={userEmail}
                isFollowed={followedIds.includes(selectedMangaId)}
                onToggleFollow={handleToggleFollow}
              />
            )}

            {currentView === 'reader' && selectedMangaId && selectedChapterId && (
              <ReaderView
                mangaId={selectedMangaId}
                chapterId={selectedChapterId}
                mangas={mangas}
                onNavigate={handleNavigate}
                userEmail={userEmail}
                isFollowed={followedIds.includes(selectedMangaId)}
                onToggleFollow={handleToggleFollow}
                onAddToHistory={handleAddToHistory}
              />
            )}

            {currentView === 'following' && (
              <FollowingView
                mangas={mangas}
                followedIds={followedIds}
                onNavigate={handleNavigate}
                onToggleFollow={handleToggleFollow}
              />
            )}

            {currentView === 'history' && (
              <HistoryView
                historyItems={historyItems}
                onNavigate={handleNavigate}
                onClearHistory={handleClearHistory}
              />
            )}

            {currentView === 'management' && (
              <ManagementView
                onNavigate={handleNavigate}
              />
            )}
          </>
        )}
      </main>

      {/* Render footer on all pages except the chapter reader to guarantee zero distractions while reading */}
      {currentView !== 'reader' && (
        <Footer onNavigate={handleNavigate} />
      )}
    </div>
  );
}
