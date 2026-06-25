import urllib.request
import json
import os
import ssl

def main():
    # Use v2 API for items list (v1 is deprecated)
    url = "https://api.warframe.market/v2/items"
    print(f"Fetching warframe.market items from {url}...")
    
    # SSL context to bypass cert revocation issues on Windows
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "Language": "en"
    }
    req = urllib.request.Request(url, headers=headers)
    
    try:
        with urllib.request.urlopen(req, context=ctx) as response:
            data = json.loads(response.read().decode())
            
        items = data.get("data", [])
        print(f"Successfully fetched {len(items)} items.")
        
        # Minify database: keep only name (n) and slug (u)
        minified_items = []
        for item in items:
            # v2 API uses 'slug' instead of 'url_name', and name is in i18n.en.name
            slug = item.get("slug")
            name = item.get("i18n", {}).get("en", {}).get("name")
            if name and slug:
                minified_items.append({
                    "n": name,
                    "u": slug
                })
                
        # Define output path
        output_dir = os.path.join("app", "src", "main", "assets")
        os.makedirs(output_dir, exist_ok=True)
        output_file = os.path.join(output_dir, "items.json")
        
        with open(output_file, "w", encoding="utf-8") as f:
            json.dump(minified_items, f, separators=(',', ':'), ensure_ascii=False)
            
        size_kb = os.path.getsize(output_file) / 1024
        print(f"Saved {len(minified_items)} items to {output_file} ({size_kb:.1f} KB).")
        
    except Exception as e:
        print(f"Error fetching items: {e}")
        raise

if __name__ == "__main__":
    main()
