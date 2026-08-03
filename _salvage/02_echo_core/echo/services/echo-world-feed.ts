/**
 * MODULE 7 — THE WORLD FEED
 * Real-Time External Intelligence & Live API Integration Layer
 *
 * Search: Brave Search API + Exa.ai neural search
 * Live feeds: SAM.gov, RSMeans, NOAA, Federal Register, OSHA, BLS, UPS/FedEx
 */

import Exa from 'exa-js';
import axios, { type AxiosInstance } from 'axios';

export interface WorldFeedConfig {
  exaApiKey: string;
  braveApiKey: string;
}

export interface SearchResult {
  title: string;
  url: string;
  snippet: string;
  source: 'brave' | 'exa';
  score: number;
  publishedDate?: string;
}

export interface SAMPosting {
  title: string;
  noticeId: string;
  naicsCode: string;
  setAside: string;
  responseDeadline: string;
  agency: string;
  url: string;
}

export interface RSMeansRate {
  csiCode: string;
  description: string;
  unit: string;
  materialCost: number;
  laborCost: number;
  totalCost: number;
  region: string;
  effectiveDate: string;
}

export class EchoWorldFeed {
  private exa: Exa;
  private braveApiKey: string;
  private http: AxiosInstance;

  constructor(config: WorldFeedConfig) {
    this.exa = new Exa(config.exaApiKey);
    this.braveApiKey = config.braveApiKey;
    this.http = axios.create({ timeout: 15000 });
  }

  /** Brave + Exa parallel search with result fusion */
  async searchWeb(query: string): Promise<SearchResult[]> {
    const [braveResults, exaResults] = await Promise.allSettled([
      this.searchBrave(query),
      this.searchExa(query),
    ]);

    const results: SearchResult[] = [];

    if (braveResults.status === 'fulfilled') {
      results.push(...braveResults.value);
    }
    if (exaResults.status === 'fulfilled') {
      results.push(...exaResults.value);
    }

    // Deduplicate by URL, keep highest score
    const seen = new Map<string, SearchResult>();
    for (const r of results) {
      const existing = seen.get(r.url);
      if (!existing || r.score > existing.score) {
        seen.set(r.url, r);
      }
    }

    return [...seen.values()].sort((a, b) => b.score - a.score);
  }

  /** Pulls live federal solicitation data from SAM.gov */
  async fetchSAMPosting(naicsCode: string): Promise<SAMPosting[]> {
    const response = await this.http.get('https://api.sam.gov/opportunities/v2/search', {
      params: {
        api_key: process.env.SAM_GOV_API_KEY,
        naicsCode,
        postedFrom: this.daysAgo(30),
        limit: 25,
      },
    });
    return (response.data.opportunitiesData ?? []).map((opp: any) => ({
      title: opp.title,
      noticeId: opp.noticeId,
      naicsCode: opp.naics?.[0]?.code ?? naicsCode,
      setAside: opp.typeOfSetAside ?? 'None',
      responseDeadline: opp.responseDeadLine,
      agency: opp.fullParentPathName,
      url: `https://sam.gov/opp/${opp.noticeId}/view`,
    }));
  }

  /** Real-time unit cost retrieval from RSMeans */
  async pullRSMeansData(csiCode: string, region?: string): Promise<RSMeansRate | null> {
    // RSMeans/Gordian API integration
    // Requires active Gordian API subscription
    try {
      const response = await this.http.get('https://api.gordian.com/v1/costdata', {
        params: { csiCode, region: region ?? 'national' },
        headers: { Authorization: `Bearer ${process.env.RSMEANS_API_KEY}` },
      });
      return response.data;
    } catch {
      console.warn(`[ECHO-WORLD-FEED] RSMeans lookup failed for CSI ${csiCode}`);
      return null;
    }
  }

  /** Active site weather alerting via NOAA */
  async monitorWeather(lat: number, lon: number): Promise<{
    forecast: string;
    alerts: Array<{ event: string; severity: string; description: string }>;
  }> {
    // NOAA Weather API — no key required
    const pointResponse = await this.http.get(`https://api.weather.gov/points/${lat},${lon}`);
    const forecastUrl = pointResponse.data.properties.forecast;
    const alertsUrl = `https://api.weather.gov/alerts/active?point=${lat},${lon}`;

    const [forecast, alerts] = await Promise.all([
      this.http.get(forecastUrl),
      this.http.get(alertsUrl),
    ]);

    return {
      forecast: forecast.data.properties.periods[0]?.detailedForecast ?? 'No forecast available',
      alerts: (alerts.data.features ?? []).map((a: any) => ({
        event: a.properties.event,
        severity: a.properties.severity,
        description: a.properties.description,
      })),
    };
  }

  /** Webhook listener for Federal Register regulatory changes */
  async streamFederalRegister(keywords: string[]): Promise<Array<{
    title: string;
    abstract: string;
    documentNumber: string;
    publicationDate: string;
    url: string;
  }>> {
    const response = await this.http.get('https://www.federalregister.gov/api/v1/documents.json', {
      params: {
        'conditions[term]': keywords.join('|'),
        'conditions[publication_date][gte]': this.daysAgo(7),
        per_page: 20,
        order: 'newest',
      },
    });
    return (response.data.results ?? []).map((doc: any) => ({
      title: doc.title,
      abstract: doc.abstract,
      documentNumber: doc.document_number,
      publicationDate: doc.publication_date,
      url: doc.html_url,
    }));
  }

  // ── Private ────────────────────────────────────────────

  private async searchBrave(query: string): Promise<SearchResult[]> {
    const response = await this.http.get('https://api.search.brave.com/res/v1/web/search', {
      params: { q: query, count: 10 },
      headers: {
        'Accept': 'application/json',
        'X-Subscription-Token': this.braveApiKey,
      },
    });
    return (response.data.web?.results ?? []).map((r: any, i: number) => ({
      title: r.title,
      url: r.url,
      snippet: r.description,
      source: 'brave' as const,
      score: 1 - i * 0.08,
    }));
  }

  private async searchExa(query: string): Promise<SearchResult[]> {
    const results = await this.exa.searchAndContents(query, {
      numResults: 10,
      useAutoprompt: true,
      text: { maxCharacters: 500 },
    });
    return results.results.map((r, i) => ({
      title: r.title ?? '',
      url: r.url,
      snippet: r.text ?? '',
      source: 'exa' as const,
      score: 1 - i * 0.08,
      publishedDate: r.publishedDate ?? undefined,
    }));
  }

  private daysAgo(days: number): string {
    const d = new Date();
    d.setDate(d.getDate() - days);
    return d.toISOString().split('T')[0];
  }
}
