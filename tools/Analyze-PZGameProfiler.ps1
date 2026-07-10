[CmdletBinding()]
param(
    [string]$CacheDir = "$env:USERPROFILE\Zomboid",
    [string]$HeaderPath = '',
    [string]$OutputDir = '',
    [int]$Top = 40,
    [int]$WorstFrameCount = 30,
    [int]$WorstFrameSpanCount = 200,
    [switch]$RenderThread
)

$ErrorActionPreference = 'Stop'

$source = @"
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Net;
using System.Text;

public static class PZGameProfilerAnalyzer
{
    class Agg
    {
        public int Key;
        public string Name;
        public int Count;
        public double TotalInclusiveMs;
        public double TotalSelfMs;
        public double MaxInclusiveMs;
        public double MaxSelfMs;
        public HashSet<int> Frames = new HashSet<int>();
    }

    class FrameSummary
    {
        public int Frame;
        public double TotalMs;
        public int SpanCount;
        public string Segment;
    }

    class SpanRow
    {
        public int Frame;
        public int Depth;
        public double StartMs;
        public double InclusiveMs;
        public double SelfMs;
        public string Name;
    }

    class ParsedFrame
    {
        public int Frame;
        public int Count;
        public int[] Keys;
        public int[] Depths;
        public double[] Starts;
        public double[] Inclusive;
        public double[] Self;
        public double TotalMs;
    }

    public static void Run(string cacheDir, string headerPath, string outputDir, int top, int worstFrameCount, int worstFrameSpanCount, bool renderThread)
    {
        string recordingDir = Path.Combine(cacheDir, "Recording");
        if (String.IsNullOrWhiteSpace(headerPath))
        {
            headerPath = FindHeaderPath(recordingDir, renderThread);
        }
        if (String.IsNullOrWhiteSpace(headerPath) || !File.Exists(headerPath))
        {
            throw new Exception("Could not find a GameProfiler header CSV. Pass -HeaderPath or check " + recordingDir + ".");
        }

        FileInfo headerFile = new FileInfo(headerPath);
        string prefix = headerFile.Name.Substring(0, headerFile.Name.Length - "_header.csv".Length);
        recordingDir = headerFile.DirectoryName;
        FileInfo[] segmentFiles = new DirectoryInfo(recordingDir).GetFiles(prefix + "_times_*.csv").OrderBy(f => f.Name).ToArray();
        if (segmentFiles.Length == 0)
        {
            throw new Exception("No segment files found for prefix '" + prefix + "' in " + recordingDir + ".");
        }

        Dictionary<int, string> keyNames = ReadKeyNames(headerPath);
        if (String.IsNullOrWhiteSpace(outputDir))
        {
            outputDir = Path.Combine(recordingDir, "GameProfilerAnalysis_" + DateTime.Now.ToString("yyyyMMdd-HHmmss"));
        }
        Directory.CreateDirectory(outputDir);

        Dictionary<int, Agg> aggregate = new Dictionary<int, Agg>();
        List<FrameSummary> frameSummaries = new List<FrameSummary>();
        int totalFrames = 0;
        long totalSpans = 0;

        Console.WriteLine("Analyzing " + segmentFiles.Length + " segment files...");
        for (int fileIndex = 0; fileIndex < segmentFiles.Length; fileIndex++)
        {
            FileInfo file = segmentFiles[fileIndex];
            if (fileIndex % 5 == 0 || fileIndex == segmentFiles.Length - 1)
            {
                Console.WriteLine("Pass 1/2: " + (fileIndex + 1) + "/" + segmentFiles.Length + " " + file.Name);
            }

            foreach (string line in File.ReadLines(file.FullName))
            {
                ParsedFrame frame = ParseFrameLine(line);
                if (frame == null) continue;

                frameSummaries.Add(new FrameSummary { Frame = frame.Frame, TotalMs = frame.TotalMs, SpanCount = frame.Count, Segment = file.Name });

                for (int i = 0; i < frame.Count; i++)
                {
                    int key = frame.Keys[i];
                    Agg agg;
                    if (!aggregate.TryGetValue(key, out agg))
                    {
                        string name;
                        if (!keyNames.TryGetValue(key, out name)) name = "<unknown:" + key.ToString(CultureInfo.InvariantCulture) + ">";
                        agg = new Agg { Key = key, Name = name };
                        aggregate[key] = agg;
                    }

                    double inc = frame.Inclusive[i];
                    double self = frame.Self[i];
                    agg.Count++;
                    agg.TotalInclusiveMs += inc;
                    agg.TotalSelfMs += self;
                    if (inc > agg.MaxInclusiveMs) agg.MaxInclusiveMs = inc;
                    if (self > agg.MaxSelfMs) agg.MaxSelfMs = self;
                    agg.Frames.Add(frame.Frame);
                }

                totalFrames++;
                totalSpans += frame.Count;
            }
        }

        List<Dictionary<string, object>> summary = new List<Dictionary<string, object>>();
        foreach (Agg agg in aggregate.Values)
        {
            Dictionary<string, object> row = new Dictionary<string, object>();
            row["Key"] = agg.Key;
            row["Name"] = agg.Name;
            row["Count"] = agg.Count;
            row["Frames"] = agg.Frames.Count;
            row["TotalInclusiveMs"] = R(agg.TotalInclusiveMs);
            row["TotalSelfMs"] = R(agg.TotalSelfMs);
            row["AvgInclusiveMs"] = R(agg.TotalInclusiveMs / Math.Max(1, agg.Count));
            row["AvgSelfMs"] = R(agg.TotalSelfMs / Math.Max(1, agg.Count));
            row["MaxInclusiveMs"] = R(agg.MaxInclusiveMs);
            row["MaxSelfMs"] = R(agg.MaxSelfMs);
            summary.Add(row);
        }

        List<Dictionary<string, object>> topInclusive = summary.OrderByDescending(r => Convert.ToDouble(r["TotalInclusiveMs"])).Take(top).ToList();
        List<Dictionary<string, object>> topSelf = summary.OrderByDescending(r => Convert.ToDouble(r["TotalSelfMs"])).Take(top).ToList();
        List<Dictionary<string, object>> topMax = summary.OrderByDescending(r => Convert.ToDouble(r["MaxInclusiveMs"])).Take(top).ToList();

        List<FrameSummary> worstFramesRaw = frameSummaries.OrderByDescending(f => f.TotalMs).Take(worstFrameCount).ToList();
        HashSet<int> worstFrameSet = new HashSet<int>(worstFramesRaw.Select(f => f.Frame));
        List<Dictionary<string, object>> worstFrames = worstFramesRaw.Select(f => new Dictionary<string, object> {
            {"Frame", f.Frame}, {"TotalMs", R(f.TotalMs)}, {"SpanCount", f.SpanCount}, {"Segment", f.Segment}
        }).ToList();

        List<SpanRow> worstSpanRows = new List<SpanRow>();
        for (int fileIndex = 0; fileIndex < segmentFiles.Length; fileIndex++)
        {
            FileInfo file = segmentFiles[fileIndex];
            if (fileIndex % 5 == 0 || fileIndex == segmentFiles.Length - 1)
            {
                Console.WriteLine("Pass 2/2: " + (fileIndex + 1) + "/" + segmentFiles.Length + " " + file.Name);
            }

            foreach (string line in File.ReadLines(file.FullName))
            {
                int comma = line.IndexOf(',');
                if (comma <= 0) continue;
                int frameNo;
                if (!Int32.TryParse(line.Substring(0, comma), NumberStyles.Integer, CultureInfo.InvariantCulture, out frameNo)) continue;
                if (!worstFrameSet.Contains(frameNo)) continue;

                ParsedFrame frame = ParseFrameLine(line);
                if (frame == null) continue;
                for (int i = 0; i < frame.Count; i++)
                {
                    string name;
                    if (!keyNames.TryGetValue(frame.Keys[i], out name)) name = "<unknown:" + frame.Keys[i].ToString(CultureInfo.InvariantCulture) + ">";
                    worstSpanRows.Add(new SpanRow {
                        Frame = frame.Frame,
                        Depth = frame.Depths[i],
                        StartMs = frame.Starts[i],
                        InclusiveMs = frame.Inclusive[i],
                        SelfMs = frame.Self[i],
                        Name = name
                    });
                }
            }
        }

        List<Dictionary<string, object>> worstFrameSpans = worstSpanRows
            .OrderBy(r => r.Frame).ThenBy(r => r.Depth).ThenBy(r => r.StartMs).Take(worstFrameSpanCount)
            .Select(r => new Dictionary<string, object> {
                {"Frame", r.Frame}, {"Depth", r.Depth}, {"StartMs", R(r.StartMs)}, {"InclusiveMs", R(r.InclusiveMs)}, {"SelfMs", R(r.SelfMs)}, {"Name", r.Name}
            }).ToList();

        WriteCsv(Path.Combine(outputDir, "top_inclusive.csv"), topInclusive, new string[] { "Key", "Name", "Count", "Frames", "TotalInclusiveMs", "AvgInclusiveMs", "MaxInclusiveMs" });
        WriteCsv(Path.Combine(outputDir, "top_self.csv"), topSelf, new string[] { "Key", "Name", "Count", "Frames", "TotalSelfMs", "AvgSelfMs", "MaxSelfMs" });
        WriteCsv(Path.Combine(outputDir, "top_max_spikes.csv"), topMax, new string[] { "Key", "Name", "Count", "Frames", "MaxInclusiveMs", "TotalInclusiveMs" });
        WriteCsv(Path.Combine(outputDir, "worst_frames.csv"), worstFrames, new string[] { "Frame", "TotalMs", "SpanCount", "Segment" });
        WriteCsv(Path.Combine(outputDir, "worst_frame_spans.csv"), worstFrameSpans, new string[] { "Frame", "Depth", "StartMs", "InclusiveMs", "SelfMs", "Name" });
        WriteMetadata(Path.Combine(outputDir, "metadata.json"), headerFile.FullName, segmentFiles.Length, totalFrames, totalSpans, outputDir);
        WriteHtml(Path.Combine(outputDir, "report.html"), headerFile.FullName, segmentFiles.Length, totalFrames, totalSpans, worstFrames, topInclusive, topSelf, topMax, worstFrameSpans);

        Console.WriteLine("Analyzed Project Zomboid GameProfiler recording.");
        Console.WriteLine("Header: " + headerFile.FullName);
        Console.WriteLine("Frames: " + totalFrames.ToString(CultureInfo.InvariantCulture));
        Console.WriteLine("Spans:  " + totalSpans.ToString(CultureInfo.InvariantCulture));
        Console.WriteLine("Output: " + outputDir);
        Console.WriteLine("Open report.html in a browser, or inspect top_inclusive.csv/top_self.csv/worst_frames.csv.");
    }

    static string FindHeaderPath(string recordingDir, bool renderThread)
    {
        if (!Directory.Exists(recordingDir)) throw new Exception("Recording directory does not exist: " + recordingDir);
        string[] patterns = renderThread
            ? new string[] { "*GameProfiler*RenderThread*_header.csv", "*GameProfiler*render*_header.csv", "*GameProfiler*_header.csv" }
            : new string[] { "*GameProfiler*MainThread*_header.csv", "*GameProfiler*main*_header.csv", "*GameProfiler*_header.csv" };
        DirectoryInfo dir = new DirectoryInfo(recordingDir);
        foreach (string pattern in patterns)
        {
            FileInfo hit = dir.GetFiles(pattern).OrderByDescending(f => f.LastWriteTime).FirstOrDefault();
            if (hit != null) return hit.FullName;
        }
        return null;
    }

    static Dictionary<int, string> ReadKeyNames(string headerPath)
    {
        Dictionary<int, string> names = new Dictionary<int, string>();
        bool inKeyTable = false;
        foreach (string line in File.ReadLines(headerPath))
        {
            string trimmed = line.Trim();
            if (trimmed == "Index,Name") { inKeyTable = true; continue; }
            if (!inKeyTable || trimmed.Length == 0) continue;
            int comma = line.IndexOf(',');
            if (comma <= 0) continue;
            int idx;
            if (Int32.TryParse(line.Substring(0, comma).Trim(), NumberStyles.Integer, CultureInfo.InvariantCulture, out idx))
            {
                names[idx] = line.Substring(comma + 1).Trim();
            }
        }
        if (names.Count == 0) throw new Exception("Could not parse KeyNamesTable from " + headerPath + ".");
        return names;
    }

    static ParsedFrame ParseFrameLine(string line)
    {
        if (String.IsNullOrWhiteSpace(line)) return null;
        string[] cells = line.Split(',');
        if (cells.Length < 5) return null;
        int frameNo;
        if (!Int32.TryParse(cells[0], NumberStyles.Integer, CultureInfo.InvariantCulture, out frameNo)) return null;

        int capacity = Math.Max(0, (cells.Length - 1) / 4);
        int[] keys = new int[capacity];
        int[] depths = new int[capacity];
        double[] starts = new double[capacity];
        double[] inclusive = new double[capacity];
        double[] self = new double[capacity];
        int actual = 0;

        for (int i = 1; i + 3 < cells.Length; i += 4)
        {
            int key, depth;
            long startTicks, lengthTicks;
            Int32.TryParse(cells[i], NumberStyles.Integer, CultureInfo.InvariantCulture, out key);
            Int32.TryParse(cells[i + 1], NumberStyles.Integer, CultureInfo.InvariantCulture, out depth);
            Int64.TryParse(cells[i + 2], NumberStyles.Integer, CultureInfo.InvariantCulture, out startTicks);
            Int64.TryParse(cells[i + 3], NumberStyles.Integer, CultureInfo.InvariantCulture, out lengthTicks);
            if (lengthTicks <= 0) continue;
            keys[actual] = key;
            depths[actual] = depth;
            starts[actual] = lengthTicks == 0 ? 0.0 : startTicks / 10000.0;
            inclusive[actual] = lengthTicks / 10000.0;
            self[actual] = inclusive[actual];
            actual++;
        }
        if (actual == 0) return null;

        int[] stack = new int[64];
        int stackCount = 0;
        for (int i = 0; i < actual; i++)
        {
            while (stackCount > 0 && depths[stack[stackCount - 1]] >= depths[i]) stackCount--;
            if (stackCount > 0)
            {
                int parentIndex = stack[stackCount - 1];
                self[parentIndex] = Math.Max(0.0, self[parentIndex] - inclusive[i]);
            }
            if (stackCount >= stack.Length)
            {
                int[] newStack = new int[stack.Length * 2];
                Array.Copy(stack, newStack, stack.Length);
                stack = newStack;
            }
            stack[stackCount++] = i;
        }

        double frameTotalMs = inclusive[0];
        for (int i = 0; i < actual; i++)
        {
            if (depths[i] == 0) { frameTotalMs = inclusive[i]; break; }
        }

        return new ParsedFrame { Frame = frameNo, Count = actual, Keys = keys, Depths = depths, Starts = starts, Inclusive = inclusive, Self = self, TotalMs = frameTotalMs };
    }

    static double R(double value) { return Math.Round(value, 4); }

    static void WriteCsv(string path, List<Dictionary<string, object>> rows, string[] columns)
    {
        using (StreamWriter writer = new StreamWriter(path, false, new UTF8Encoding(true)))
        {
            writer.WriteLine(String.Join(",", columns.Select(CsvEscape).ToArray()));
            foreach (Dictionary<string, object> row in rows)
            {
                writer.WriteLine(String.Join(",", columns.Select(c => CsvEscape(row.ContainsKey(c) ? row[c] : null)).ToArray()));
            }
        }
    }

    static string CsvEscape(object value)
    {
        string s = value == null ? "" : Convert.ToString(value, CultureInfo.InvariantCulture);
        if (s.IndexOfAny(new char[] { ',', '"', '\r', '\n' }) >= 0) return "\"" + s.Replace("\"", "\"\"") + "\"";
        return s;
    }

    static void WriteMetadata(string path, string headerPath, int segmentFileCount, int parsedFrames, long parsedSpans, string outputDir)
    {
        StringBuilder sb = new StringBuilder();
        sb.AppendLine("{");
        sb.AppendLine("  \"HeaderPath\": \"" + JsonEscape(headerPath) + "\",");
        sb.AppendLine("  \"SegmentFileCount\": " + segmentFileCount.ToString(CultureInfo.InvariantCulture) + ",");
        sb.AppendLine("  \"ParsedFrames\": " + parsedFrames.ToString(CultureInfo.InvariantCulture) + ",");
        sb.AppendLine("  \"ParsedSpans\": " + parsedSpans.ToString(CultureInfo.InvariantCulture) + ",");
        sb.AppendLine("  \"OutputDir\": \"" + JsonEscape(outputDir) + "\"");
        sb.AppendLine("}");
        File.WriteAllText(path, sb.ToString(), new UTF8Encoding(true));
    }

    static string JsonEscape(string s)
    {
        return (s ?? "").Replace("\\", "\\\\").Replace("\"", "\\\"");
    }

    static void WriteHtml(string path, string headerPath, int segmentCount, int frames, long spans, List<Dictionary<string, object>> worstFrames, List<Dictionary<string, object>> topInclusive, List<Dictionary<string, object>> topSelf, List<Dictionary<string, object>> topMax, List<Dictionary<string, object>> worstFrameSpans)
    {
        StringBuilder html = new StringBuilder();
        html.AppendLine("<!doctype html><html><head><meta charset=\"utf-8\">");
        html.AppendLine("<style>");
        html.AppendLine("body { font-family: Segoe UI, Arial, sans-serif; margin: 24px; color: #202020; }");
        html.AppendLine("h1 { margin-bottom: 0; }");
        html.AppendLine(".meta { color: #555; margin-top: 6px; }");
        html.AppendLine("table { border-collapse: collapse; margin: 12px 0 28px 0; width: 100%; font-size: 13px; }");
        html.AppendLine("th, td { border: 1px solid #ddd; padding: 6px 8px; text-align: left; vertical-align: top; }");
        html.AppendLine("th { background: #f2f2f2; position: sticky; top: 0; }");
        html.AppendLine("tr:nth-child(even) { background: #fafafa; }");
        html.AppendLine("code { background: #f6f6f6; padding: 2px 4px; }");
        html.AppendLine("</style></head><body>");
        html.AppendLine("<h1>Project Zomboid GameProfiler Analysis</h1>");
        html.AppendLine("<p class='meta'>Header: <code>" + WebUtility.HtmlEncode(headerPath) + "</code><br>Frames: " + frames + " | Spans: " + spans + " | Segments: " + segmentCount + "</p>");
        AppendHtmlTable(html, "Worst Frames", worstFrames, new string[] { "Frame", "TotalMs", "SpanCount", "Segment" });
        AppendHtmlTable(html, "Top Inclusive Time", topInclusive, new string[] { "Key", "Name", "Count", "Frames", "TotalInclusiveMs", "AvgInclusiveMs", "MaxInclusiveMs" });
        AppendHtmlTable(html, "Top Self Time", topSelf, new string[] { "Key", "Name", "Count", "Frames", "TotalSelfMs", "AvgSelfMs", "MaxSelfMs" });
        AppendHtmlTable(html, "Largest Single Spikes", topMax, new string[] { "Key", "Name", "Count", "Frames", "MaxInclusiveMs", "TotalInclusiveMs" });
        AppendHtmlTable(html, "Spans From Worst Frames", worstFrameSpans, new string[] { "Frame", "Depth", "StartMs", "InclusiveMs", "SelfMs", "Name" });
        html.AppendLine("</body></html>");
        File.WriteAllText(path, html.ToString(), new UTF8Encoding(true));
    }

    static void AppendHtmlTable(StringBuilder html, string title, List<Dictionary<string, object>> rows, string[] columns)
    {
        html.AppendLine("<h2>" + WebUtility.HtmlEncode(title) + "</h2>");
        html.AppendLine("<table><thead><tr>");
        foreach (string col in columns) html.AppendLine("<th>" + WebUtility.HtmlEncode(col) + "</th>");
        html.AppendLine("</tr></thead><tbody>");
        foreach (Dictionary<string, object> row in rows)
        {
            html.AppendLine("<tr>");
            foreach (string col in columns)
            {
                object value;
                row.TryGetValue(col, out value);
                html.AppendLine("<td>" + WebUtility.HtmlEncode(value == null ? "" : Convert.ToString(value, CultureInfo.InvariantCulture)) + "</td>");
            }
            html.AppendLine("</tr>");
        }
        html.AppendLine("</tbody></table>");
    }
}
"@

if (-not ('PZGameProfilerAnalyzer' -as [type])) {
    Add-Type -TypeDefinition $source -Language CSharp
}

[PZGameProfilerAnalyzer]::Run($CacheDir, $HeaderPath, $OutputDir, $Top, $WorstFrameCount, $WorstFrameSpanCount, [bool]$RenderThread)
