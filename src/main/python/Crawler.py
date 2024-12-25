#!/usr/bin/env python3
# note: chmod +x src/main/python/Crawler.py

import requests

wp_api_url = "https://wptavern.com/wp-json/wp/v2/posts" 

def fetch_posts(api_url, params=None):
    response = requests.get(api_url, params=params)
    if response.status_code == 200:
        return response.json()
    else:
        print(f"Failed to fetch posts: {response.status_code} - {response.text}")
        return []

query_params = {
    'context': 'view',
    'page': 1,
    'per_page': 10,
    # 'search': 'example',
    'after': '2023-01-01T00:00:00',
    'order': 'asc',
    'orderby': 'date'
}

if __name__ == "__main__":
    posts = fetch_posts(wp_api_url, params=query_params).take(1)
    print(f"Total posts fetched: {len(posts)}")
    for post in posts:
        print(f"Title: {post['title']['rendered']}")
        print(f"Content: {post['content']['rendered']}")
        print(f"Date: {post['date']}")
        print(f"id: {post['id']}")
        print(f"guid: {post['guid']}")
        print(f"link: {post['link']}")
        print(f"modified_gmt: {post['modified_gmt']}")
        print("-" * 80)