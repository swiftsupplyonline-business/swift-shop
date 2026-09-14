# Task Artifact - Swift Shop Social Feed Implementation

## Summary of Changes

### 1. Domain Layer (`FeedEngine.kt`)
- Added new abstract methods to the `FeedRepository` interface:
  - `suspend fun getPost(postId: String): Result<FeedPost?>`
  - `fun observePostComments(postId: String): Flow<List<Comment>>`
  - `suspend fun postPostComment(comment: Comment): Result<String>`
  - `suspend fun deletePostComment(commentId: String): Result<Unit>`
- Implemented and added 4 new constructor-injected use cases:
  - `GetPostUseCase`
  - `ObservePostCommentsUseCase`
  - `PostPostCommentUseCase`
  - `DeletePostCommentUseCase`

### 2. Data Layer (`FirebaseFeedRepository.kt`)
- Implemented the 4 new repository methods matching the interface additions:
  - `getPost`: Fetches single document from the `"posts"` collection and maps to the domain entity.
  - `observePostComments`: Streams comments ordered by chronological order (`"createdAt"` ascending) for a given `postId`.
  - `postPostComment`: Atomically adds a new comment to the `"comments"` collection and increments the `commentCount` inside the parent post document using `FieldValue.increment(1)`.
  - `deletePostComment`: Deletes a comment document by its unique ID.

### 3. Core UI Components (`Cards.kt`)
- Created a robust relative timestamp formatter helper function `formatRelativeTime(createdAt: Long): String`.
- Removed the hardcoded `"just now"` text, replacing it with the newly created formatted output (< 1m: "just now", < 60m: "Xm", < 24h: "Xh", < 7d: "Xd", else: "Xw").
- Integrated support for the `PostType.REEL` format inside `PostCard`. When a post is identified as a Reel, a click-to-play thumbnail with a central overlay play icon is presented. Tapping the thumbnail launches the inline `SwiftVideoPlayer` within the post's boundaries without triggering the top-level full navigation callback.

### 4. Home Feature (`HomeScreen.kt`)
- Replaced the placeholder `AsyncImage` inside `ReelItem` with a fully lifecycle-aware `SwiftVideoPlayer` component linked to the `isActive` state of the vertical pager.
- Created `ReelCommentViewModel` and a modular `CommentBottomSheet` dialog right inside the file.
- Enabled the comment trigger action: clicking the chat bubble inside `ReelItem` pops up the comments tray from the bottom to allow viewing and composing real-time comments.

### 5. Posts Feature (`PostScreens.kt`)
- Created `PostDetailViewModel` handling post fetching, like toggling, comment streams, and comment submission.
- Replaced the dummy `PostDetailScreen` template with a fully functional layout featuring the unified `PostCard` layout (with full support for images/reels playback), a chronological scrollable stream of comments, and an interactive inline comment composer at the bottom.
